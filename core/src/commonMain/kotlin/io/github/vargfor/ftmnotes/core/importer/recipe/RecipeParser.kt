package io.github.vargfor.ftmnotes.core.importer.recipe

import com.fleeksoft.ksoup.Ksoup
import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.food.engine.*
import io.github.vargfor.ftmnotes.core.importer.HtmlToMarkdown
import io.github.vargfor.ftmnotes.core.model.*
import kotlinx.serialization.json.*
import kotlin.math.ceil

data class RecipeDraft(val document: RecipeDocument, val imageUrls: List<String> = emptyList(), val imageBytes: List<RecipeImage> = emptyList())
data class RecipeImage(val bytes: ByteArray, val mime: String, val name: String)
data class RecipeParseResult(val recipes: List<RecipeDraft>, val issues: List<ReadIssue>)

object RecipeParser {
    fun absoluteImageUrl(raw: String, base: String): String {
        val image = requireNotNull(Ksoup.parse("<img>", base).selectFirst("img"))
        image.attr("src", raw)
        return image.absUrl("src")
    }
    fun parseHtml(html: String, url: String): RecipeParseResult {
        val issues = mutableListOf<ReadIssue>()
        val page = Ksoup.parse(html, url)
        val blocks = page.select("script[type=application/ld+json]").map { it.data() }.toMutableList()
        if (html.trimStart().startsWith('{') || html.trimStart().startsWith('[')) blocks += html
        val recipes = mutableListOf<RecipeDraft>()
        blocks.forEachIndexed { i, block ->
            try {
                val json = FtmFormat.writer.parseToJsonElement(block)
                collectRecipes(json, 0).forEach { obj -> recipes += convert(obj, url, issues) }
            } catch (e: Exception) { issues += ReadIssue(ReadIssue.Severity.Warning, "$.jsonLd[$i]", e.message ?: "Unreadable structured recipe") }
        }
        if (recipes.isEmpty()) issues += ReadIssue(ReadIssue.Severity.Error, "$", "No schema.org Recipe was found. Paste the recipe into the AI import prompt or create it manually.")
        return RecipeParseResult(recipes.distinctBy { it.document.title to it.document.ingredientGroups }, issues)
    }

    private fun collectRecipes(element: JsonElement, depth: Int): List<JsonObject> {
        if (depth > 32) return emptyList()
        return when (element) {
            is JsonArray -> element.flatMap { collectRecipes(it, depth + 1) }
            is JsonObject -> {
                val type = element["@type"]
                val names = if (type is JsonArray) type.mapNotNull(::text) else listOfNotNull(text(type))
                if (names.any { it.substringAfterLast('/').equals("Recipe", true) }) listOf(element)
                else element.values.flatMap { collectRecipes(it, depth + 1) }
            }
            else -> emptyList()
        }
    }

    private fun convert(obj: JsonObject, url: String, issues: MutableList<ReadIssue>): RecipeDraft {
        val name = text(obj["name"]).orEmpty()
        val ingredients = (obj["recipeIngredient"] as? JsonArray)?.mapNotNull(::text).orEmpty()
        if (ingredients.isEmpty()) issues += ReadIssue(ReadIssue.Severity.Warning, "$.recipeIngredient", "No ingredients were supplied")
        val yields = if (obj["recipeYield"] is JsonArray) text(obj["recipeYield"]!!.jsonArray.firstOrNull()) else text(obj["recipeYield"])
        val source = text(obj["url"])?.takeIf { it.startsWith("http") } ?: url
        val nutrition = (obj["nutrition"] as? JsonObject)?.filterKeys { !it.startsWith('@') }?.mapNotNull { (key, value) -> text(value)?.let { key to it } }?.toMap().orEmpty()
        val rating = (obj["aggregateRating"] as? JsonObject)?.get("ratingValue")?.let(::text)?.toDoubleOrNull()?.toInt()?.coerceIn(1, 5)
        val recipe = RecipeDocument(title = plain(name), description = plain(text(obj["description"]).orEmpty()),
            servings = servings(yields), prepMinutes = minutes(text(obj["prepTime"])), cookMinutes = minutes(text(obj["cookTime"])), totalMinutes = minutes(text(obj["totalTime"])),
            ingredientGroups = ingredientGroups(ingredients), steps = instructions(obj["recipeInstructions"]), sourceUrl = source,
            sourceName = (obj["author"] as? JsonObject)?.get("name")?.let(::text),
            nutrition = nutrition, rating = rating,
            tags = (obj["recipeCategory"] as? JsonArray)?.mapNotNull(::text) ?: listOfNotNull(text(obj["recipeCategory"])))
        fun images(element: JsonElement?): List<String> = when (element) {
            is JsonPrimitive -> listOf(element.content)
            is JsonArray -> element.flatMap(::images)
            is JsonObject -> listOfNotNull(text(element["url"]) ?: text(element["contentUrl"]))
            else -> emptyList()
        }
        return RecipeDraft(recipe, images(obj["image"]).distinct())
    }

    fun ingredientGroups(lines: List<String>): List<IngredientGroup> {
        val result = mutableListOf<IngredientGroup>()
        var heading: String? = null
        val items = mutableListOf<Ingredient>()
        fun flush() { if (items.isNotEmpty() || heading != null) { result += IngredientGroup(name = heading, items = items.toList()); items.clear() } }
        lines.filter(String::isNotBlank).forEach { line ->
            val clean = plain(line).trim()
            if (clean.endsWith(':') && AmountParser.prefix(clean) == null) { flush(); heading = clean.dropLast(1) }
            else items += IngredientParser.parse(clean)
        }
        flush()
        return result
    }

    fun instructions(value: JsonElement?, section: String? = null): List<RecipeStep> = when (value) {
        is JsonArray -> value.flatMap { instructions(it, section) }
        is JsonObject -> {
            if ("itemListElement" in value) instructions(value["itemListElement"], text(value["name"]) ?: section)
            else (text(value["text"]) ?: text(value["name"]))?.let { listOf(RecipeStep(text = plain(it), section = section)) }.orEmpty()
        }
        is JsonPrimitive -> {
            val raw = value.content
            val html = Ksoup.parse(raw)
            val list = html.select("li")
            val lines = if (list.isNotEmpty()) list.map { it.text() } else if (Regex("</?[A-Za-z][^>]*>").containsMatchIn(raw))
                HtmlToMarkdown.convert(raw).lines().map(::stripMarkdown).filter(String::isNotBlank)
                else raw.lines().map(::stripMarkdown).filter(String::isNotBlank)
            lines.map { RecipeStep(text = it.replace(Regex("""^\d+[.)]\s+"""), ""), section = section) }
        }
        else -> emptyList()
    }

    fun servings(raw: String?): Servings {
        if (raw.isNullOrBlank()) return Servings()
        val start = raw.indexOfFirst { it.isDigit() || it in "½¼¾⅓⅔" }
        val parsed = if (start >= 0) AmountParser.prefix(raw.substring(start)) else null
        return if (parsed != null && parsed.quantity.min > 0) Servings(parsed.quantity.min, parsed.remainder)
        else Servings(1.0, raw)
    }

    fun minutes(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        raw.toDoubleOrNull()?.let { return if (it.isFinite() && it >= 0 && it <= Int.MAX_VALUE) ceil(it).toInt() else null }
        val iso = Regex("""^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""", RegexOption.IGNORE_CASE).matchEntire(raw)
        val seconds = if (iso != null) {
            val parts = iso.groupValues.drop(1).map { it.toDoubleOrNull() ?: 0.0 }
            parts[0] * 86400 + parts[1] * 3600 + parts[2] * 60 + parts[3]
        } else StepTimers.seconds(raw)?.toDouble() ?: return null
        return ceil(seconds / 60).takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt()
    }

    fun plain(value: String): String = stripMarkdown(HtmlToMarkdown.convert(value)).trim()
    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}
