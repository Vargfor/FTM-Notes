package io.github.vargfor.ftmnotes.core.format

import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.core.model.FtmDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ReadIssue(val severity: Severity, val path: String, val message: String) {
    enum class Severity { Info, Warning, Error }
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

    fun read(input: String): ReadResult {
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
            decodeOne(element, path, issues)
        }
        return ReadResult(documents, issues)
    }

    private fun decodeOne(element: JsonElement, path: String, issues: MutableList<ReadIssue>): FtmDocument? {
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
        val serializer = FtmFormat.supported[type]
        if (serializer == null) {
            issues += error("$path.type", "\"${type.wire}\" documents aren't supported by this version of the app yet")
            return null
        }
        if (type.wire != rawType) {
            issues += ReadIssue(ReadIssue.Severity.Info, "$path.type", "Interpreted \"$rawType\" as \"${type.wire}\"")
        }

        val known = (0 until serializer.descriptor.elementsCount).map(serializer.descriptor::getElementName).toSet()
        element.keys.filter { it != FtmFormat.DISCRIMINATOR && it !in known }.forEach { key ->
            issues += ReadIssue(ReadIssue.Severity.Warning, "$path.$key", "Ignored unknown field \"$key\"")
        }

        val normalised = JsonObject(element.filterKeys { it != FtmFormat.DISCRIMINATOR })
        return try {
            FtmFormat.lenient.decodeFromJsonElement(serializer, normalised)
        } catch (e: SerializationException) {
            issues += error(path, e.message?.lineSequence()?.firstOrNull() ?: "Could not read this document")
            null
        } catch (e: IllegalArgumentException) {
            issues += error(path, e.message ?: "Could not read this document")
            null
        }
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
