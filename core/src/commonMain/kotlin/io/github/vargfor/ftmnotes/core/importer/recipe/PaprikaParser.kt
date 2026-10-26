package io.github.vargfor.ftmnotes.core.importer.recipe

import io.github.vargfor.ftmnotes.core.backup.BackupArchive
import io.github.vargfor.ftmnotes.core.backup.readLimited
import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.model.*
import kotlinx.serialization.json.*
import okio.*
import okio.ByteString.Companion.decodeBase64
import okio.Path.Companion.toPath

/** Paprika bulk exports contain gzip JSON entries; images are commonly embedded as base64. */
object PaprikaParser {
    fun parse(fs: FileSystem, file: Path): RecipeParseResult {
        val issues = mutableListOf<ReadIssue>()
        val recipes = mutableListOf<RecipeDraft>()
        val zip = fs.openZip(file)
        val entries = zip.listRecursively("/".toPath()).filter { zip.metadata(it).isRegularFile }.toList()
        require(entries.size <= 10000) { "Too many entries in recipe archive" }
        for (path in entries.filter { it.name.endsWith(".paprikarecipe", true) || it.name.endsWith(".json", true) }) {
            try {
                require((zip.metadata(path).size ?: 0) <= BackupArchive.MAX_ENTRY_BYTES) { "Recipe entry is too large" }
                val bytes = zip.source(path).buffer().use { it.readLimited(BackupArchive.MAX_ENTRY_BYTES) }
                val source = Buffer().write(bytes)
                val json = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                    GzipSource(source).buffer().use { it.readLimited(BackupArchive.MAX_ENTRY_BYTES).decodeToString() }
                    else bytes.decodeToString()
                val obj = FtmFormat.writer.parseToJsonElement(json).jsonObject
                fun field(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
                val title = field("name") ?: field("title") ?: error("Recipe has no name")
                val categories = (obj["categories"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val recipe = RecipeDocument(title = title, description = field("description").orEmpty(),
                    servings = RecipeParser.servings(field("servings")),
                    prepMinutes = RecipeParser.minutes(field("prep_time")), cookMinutes = RecipeParser.minutes(field("cook_time")), totalMinutes = RecipeParser.minutes(field("total_time")),
                    ingredientGroups = RecipeParser.ingredientGroups(field("ingredients").orEmpty().lines()),
                    steps = RecipeParser.instructions(JsonPrimitive(field("directions").orEmpty())),
                    notes = RecipeParser.plain(field("notes").orEmpty()), tags = categories, sourceUrl = field("source_url"), sourceName = field("source"),
                    rating = field("rating")?.toDoubleOrNull()?.toInt()?.takeIf { it in 1..5 },
                    nutrition = field("nutritional_info")?.takeIf(String::isNotBlank)?.let { mapOf("info" to it) }.orEmpty())
                val images = mutableListOf<RecipeImage>()
                fun decodePhoto(encoded: String?, name: String) {
                    if (encoded.isNullOrBlank()) return
                    val data = requireNotNull(encoded.substringAfter("base64,", encoded).decodeBase64()) { "Unreadable photo data" }
                    require(data.size <= BackupArchive.MAX_ENTRY_BYTES) { "Photo is too large" }
                    val mime = if (data.hex().startsWith("89504e47")) "image/png" else "image/jpeg"
                    images += RecipeImage(data.toByteArray(), mime, name)
                }
                try {
                    decodePhoto(field("photo_data"), field("photo") ?: "photo.jpg")
                    (obj["photos"] as? JsonArray)?.forEachIndexed { i, photo ->
                        val value = photo as? JsonObject
                        decodePhoto((value?.get("data") as? JsonPrimitive)?.contentOrNull ?: (value?.get("photo_data") as? JsonPrimitive)?.contentOrNull, "photo-$i.jpg")
                    }
                } catch (e: Exception) { issues += ReadIssue(ReadIssue.Severity.Warning, path.toString(), e.message ?: "Photo could not be read") }
                recipes += RecipeDraft(recipe, imageBytes = images)
            } catch (e: Exception) { issues += ReadIssue(ReadIssue.Severity.Warning, path.toString(), e.message ?: "Recipe could not be read") }
        }
        if (recipes.isEmpty()) issues += ReadIssue(ReadIssue.Severity.Error, "$", "No Paprika recipes found")
        return RecipeParseResult(recipes, issues)
    }
}
