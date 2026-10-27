package io.github.vargfor.ftmnotes.core.format

import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.core.model.FtmDocument
import io.github.vargfor.ftmnotes.core.model.NoteColor
import io.github.vargfor.ftmnotes.core.model.Ingredient
import io.github.vargfor.ftmnotes.core.food.engine.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.*

@Serializable
data class ReadIssue(val severity: Severity, val path: String, val message: String) {
    @Serializable enum class Severity { Info, Warning, Error }
}

data class ReadResult(val documents: List<FtmDocument>, val issues: List<ReadIssue>) {
    val errors: List<ReadIssue> get() = issues.filter { it.severity == ReadIssue.Severity.Error }
}

/**
 * Lenient FTM reader. Accepts an envelope, a bare array or a single document, possibly wrapped in
 * Markdown code fences or surrounded by chatty AI prose. One bad document never blocks the rest:
 * each problem becomes a [ReadIssue] with a JSON path and a human-readable message.
 */
object FtmReader {

    /**
     * @param allowSettings settings documents are only accepted from our own backups, never from
     *   user/AI imports (an imported file must not be able to change app behaviour).
     */
    fun read(input: String, allowSettings: Boolean = false): ReadResult {
        val issues = mutableListOf<ReadIssue>()
        val json = extractJson(input, issues) ?: return ReadResult(emptyList(), issues)

        val root = try {
            FtmFormat.lenient.parseToJsonElement(json)
        } catch (e: SerializationException) {
            issues += error("$", "This isn't valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            return ReadResult(emptyList(), issues)
        }

        val (elements, basePath) = when {
            root is JsonArray -> root to "$"
            root is JsonObject && "documents" in root -> {
                checkVersion(root, issues)
                val docs = root["documents"]
                if (docs !is JsonArray) {
                    issues += error("$.documents", "\"documents\" must be a list")
                    return ReadResult(emptyList(), issues)
                }
                docs to "$.documents"
            }
            root is JsonObject -> JsonArray(listOf(root)) to "$"
            else -> {
                issues += error("$", "Expected a JSON object or list of documents")
                return ReadResult(emptyList(), issues)
            }
        }

        val documents = elements.mapIndexedNotNull { index, element ->
            val path = if (root is JsonObject && basePath == "$") "$" else "$basePath[$index]"
            decodeOne(element, path, allowSettings, issues)
        }
        return ReadResult(documents, issues)
    }

    private fun decodeOne(
        element: JsonElement,
        path: String,
        allowSettings: Boolean,
        issues: MutableList<ReadIssue>,
    ): FtmDocument? {
        if (element !is JsonObject) {
            issues += error(path, "Each document must be a JSON object")
            return null
        }
        val rawType = (element[FtmFormat.DISCRIMINATOR] as? JsonPrimitive)?.content
        if (rawType.isNullOrBlank()) {
            issues += error(path, "Missing \"type\". Use one of: ${DocumentType.entries.joinToString { it.wire }}")
            return null
        }
        val type = DocumentType.fromWire(rawType)
        if (type == null) {
            issues += error("$path.type", "Unknown type \"$rawType\". Use one of: ${DocumentType.entries.joinToString { it.wire }}")
            return null
        }
        if (type == DocumentType.Settings && !allowSettings) {
            issues += error("$path.type", "Settings can't be imported; only notes and other content can")
            return null
        }
        val serializer = FtmFormat.supported[type]
        if (serializer == null) {
            issues += error("$path.type", "\"${type.wire}\" documents aren't supported by this version of the app yet")
            return null
        }
        if (type.wire != rawType) {
            issues += ReadIssue(ReadIssue.Severity.Info, "$path.type", "Interpreted \"$rawType\" as \"${type.wire}\"")
        }

        val known = (0 until serializer.descriptor.elementsCount).map(serializer.descriptor::getElementName).toSet()
        val aliases = when(type) { DocumentType.Recipe -> setOf("ingredients", "instructions"); DocumentType.WorkoutTemplate -> setOf("exercises"); else -> emptySet() }
        element.keys.filter { it != FtmFormat.DISCRIMINATOR && it !in known && it !in aliases }.forEach { key ->
            issues += ReadIssue(ReadIssue.Severity.Warning, "$path.$key", "Ignored unknown field \"$key\"")
        }

        val normalised = normalise(element, type, path, issues)
        return try {
            val document = FtmFormat.lenient.decodeFromJsonElement(serializer, normalised)
            val trainingIssues = io.github.vargfor.ftmnotes.core.training.TrainingValidation.issues(document, path)
            issues += trainingIssues
            if (trainingIssues.any { it.severity == ReadIssue.Severity.Error }) null else document
        } catch (e: SerializationException) {
            issues += error(path, e.message?.lineSequence()?.firstOrNull() ?: "Could not read this document")
            null
        } catch (e: IllegalArgumentException) {
            issues += error(path, e.message ?: "Could not read this document")
            null
        }
    }

    /** Fixes common deviations before decoding: missing checklist `kind`, non-canonical colours. */
    private fun normalise(element: JsonObject, type: DocumentType, path: String, issues: MutableList<ReadIssue>): JsonObject {
        val fields = element.filterKeys { it != FtmFormat.DISCRIMINATOR }.toMutableMap()
        io.github.vargfor.ftmnotes.core.training.TrainingValidation.normalise(fields, type, path, issues)
        if (type == DocumentType.Recipe) {
            if ("ingredients" in fields && "ingredientGroups" !in fields) {
                fields["ingredientGroups"] = JsonArray(listOf(buildJsonObject { put("items", fields.getValue("ingredients")) }))
                fields.remove("ingredients")
                issues += ReadIssue(ReadIssue.Severity.Info, "$path.ingredients", "Grouped flat ingredients")
            }
            fields["ingredientGroups"]?.let { groups ->
                if (groups is JsonArray) fields["ingredientGroups"] = JsonArray(groups.mapIndexed { gi, group ->
                    if (group !is JsonObject) group else JsonObject(group.toMutableMap().apply {
                        (group["items"] as? JsonArray)?.let { items -> put("items", JsonArray(items.mapIndexed { ii, item -> normaliseIngredient(item, "$path.ingredientGroups[$gi].items[$ii]", issues) })) }
                    })
                })
            }
            val steps = fields["steps"] ?: fields.remove("instructions")
            if (steps is JsonArray) fields["steps"] = JsonArray(steps.map { if (it is JsonPrimitive && it.isString) buildJsonObject { put("text", it.content) } else it })
            (fields["servings"] as? JsonPrimitive)?.let { value ->
                val amount = AmountParser.prefix(value.content)
                if (amount != null) fields["servings"] = buildJsonObject { put("amount", amount.quantity.min); if (amount.remainder.isNotBlank()) put("label", amount.remainder) }
            }
        }
        if (type == DocumentType.ShoppingList) {
            (fields["items"] as? JsonArray)?.let { items -> fields["items"] = JsonArray(items.mapIndexed { i, item ->
                if (item is JsonPrimitive && item.isString) FtmFormat.writer.encodeToJsonElement(io.github.vargfor.ftmnotes.core.model.ShoppingItem.serializer(), ShoppingLists.quickAdd(item.content))
                else normaliseIngredient(item, "$path.items[$i]", issues)
            }) }
        }

        if (type == DocumentType.Note && "kind" !in fields && (fields["items"] as? JsonArray)?.isNotEmpty() == true) {
            fields["kind"] = JsonPrimitive("checklist")
            issues += ReadIssue(ReadIssue.Severity.Info, "$path.kind", "Treated as a checklist because it has items")
        }

        val rawColor = (fields["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (rawColor != null) {
            when (val parsed = NoteColor.parse(rawColor)) {
                is NoteColor.Parsed.Color -> {
                    fields["color"] = JsonPrimitive(parsed.color.wire)
                    if (rawColor != parsed.color.wire) {
                        issues += ReadIssue(ReadIssue.Severity.Info, "$path.color", "Interpreted colour \"$rawColor\" as \"${parsed.color.wire}\"")
                    }
                }
                NoteColor.Parsed.Default -> fields.remove("color")
                NoteColor.Parsed.Unknown -> {
                    fields.remove("color")
                    issues += ReadIssue(
                        ReadIssue.Severity.Warning, "$path.color",
                        "Unknown colour \"$rawColor\"; using no colour. Use one of: ${NoteColor.entries.joinToString { it.wire }}",
                    )
                }
            }
        }
        return JsonObject(fields)
    }

    private fun normaliseIngredient(element: JsonElement, path: String, issues: MutableList<ReadIssue>): JsonElement {
        if (element is JsonPrimitive && element.isString) {
            issues += ReadIssue(ReadIssue.Severity.Info, path, "Parsed ingredient text")
            return FtmFormat.writer.encodeToJsonElement(Ingredient.serializer(), IngredientParser.parse(element.content))
        }
        if (element !is JsonObject) return element
        val fields = element.toMutableMap()
        (fields["unit"] as? JsonPrimitive)?.content?.let { raw ->
            Units.find(raw)?.let { unit -> if (unit.id != raw) {
                fields["unit"] = JsonPrimitive(unit.id)
                issues += ReadIssue(ReadIssue.Severity.Info, "$path.unit", "Normalised unit \"$raw\"")
            } }
        }
        (fields["quantity"] as? JsonPrimitive)?.takeIf { it.isString }?.let { raw ->
            AmountParser.parse(raw.content)?.let { quantity ->
                fields["quantity"] = FtmFormat.writer.encodeToJsonElement(QuantitySerializer, quantity)
                issues += ReadIssue(ReadIssue.Severity.Info, "$path.quantity", "Parsed quantity \"${raw.content}\"")
            }
        }
        return JsonObject(fields)
    }

    private fun checkVersion(root: JsonObject, issues: MutableList<ReadIssue>) {
        val version = root["ftm"]?.jsonPrimitive?.intOrNull ?: return
        if (version > FtmFormat.VERSION) {
            issues += ReadIssue(
                ReadIssue.Severity.Warning, "$.ftm",
                "File uses FTM format v$version; this app reads v${FtmFormat.VERSION}. Newer fields may be skipped.",
            )
        }
    }

    /** Strips ``` fences and any prose before/after the JSON payload. */
    internal fun extractJson(input: String, issues: MutableList<ReadIssue>): String? {
        var text = input.trim().removePrefix("﻿")
        val fence = Regex("```[a-zA-Z0-9_-]*\\s*\\n([\\s\\S]*?)\\n?```").find(text)
        if (fence != null) {
            if (fence.range.first > 0 || fence.range.last < text.lastIndex) {
                issues += ReadIssue(ReadIssue.Severity.Info, "$", "Ignored text outside the code block")
            }
            text = fence.groupValues[1].trim()
        }
        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = text.indexOfLast { it == '}' || it == ']' }
        if (start == -1 || end <= start) {
            issues += error("$", "No JSON found. Paste the AI's answer including the { … } part.")
            return null
        }
        if (start > 0 || end < text.lastIndex) {
            issues += ReadIssue(ReadIssue.Severity.Info, "$", "Ignored text around the JSON")
        }
        return text.substring(start, end + 1)
    }

    private fun error(path: String, message: String) = ReadIssue(ReadIssue.Severity.Error, path, message)
}
