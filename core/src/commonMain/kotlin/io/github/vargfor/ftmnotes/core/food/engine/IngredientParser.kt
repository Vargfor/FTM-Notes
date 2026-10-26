package io.github.vargfor.ftmnotes.core.food.engine

import io.github.vargfor.ftmnotes.core.model.*

/** Conservative parsing: the source line is always retained and unknown text stays editable. */
object IngredientParser {
    fun parse(raw: String): Ingredient {
        var text = AmountParser.normalise(raw.trim().removePrefix("- ").removePrefix("• "))
        val parsed = AmountParser.prefix(text)
        var quantity = parsed?.quantity
        if (parsed != null) text = parsed.remainder
        else if (text.startsWith("a ", true) || text.startsWith("an ", true)) {
            val withoutArticle = text.substringAfter(' ')
            if (Units.prefix(withoutArticle) != null) { quantity = Quantity(1.0); text = withoutArticle }
        }
        var packageQuantity: Quantity? = null
        var packageUnit: String? = null
        val packageMatch = Regex("""^\(([^)]+)\)\s*""").find(text)
        if (packageMatch != null) {
            val amount = AmountParser.prefix(packageMatch.groupValues[1])
            val unit = amount?.let { Units.find(it.remainder) }
            if (amount != null && unit != null) {
                packageQuantity = amount.quantity; packageUnit = unit.id
                text = text.substring(packageMatch.range.last + 1)
            }
        }
        val match = Units.prefix(text)
        var unit = match?.unit?.id
        if (match != null) { text = match.rest; if (quantity == null) quantity = Quantity(1.0) }
        text = text.replace(Regex("""^(?:of|av)\s+""", RegexOption.IGNORE_CASE), "")
        var note = ""
        val comma = text.indexOf(',')
        if (comma >= 0) { note = text.substring(comma + 1).trim(); text = text.substring(0, comma).trim() }
        Regex("""\s*\(([^)]+)\)\s*$""").find(text)?.let {
            note = listOf(note, it.groupValues[1]).filter(String::isNotBlank).joinToString("; ")
            text = text.substring(0, it.range.first).trim()
        }
        val toTaste = Regex("""\b(to taste|as needed|etter smak|etter behov)\b""", RegexOption.IGNORE_CASE)
        val optional = Regex("""\b(optional|valgfritt|valgfri)\b""", RegexOption.IGNORE_CASE)
        val scalable = quantity != null && !toTaste.containsMatchIn(raw)
        val isOptional = optional.containsMatchIn(raw)
        toTaste.find(text)?.let { note = listOf(note, it.value).filter(String::isNotBlank).joinToString("; "); text = text.replace(toTaste, "").trim(' ', ',', '(', ')') }
        text = text.replace(optional, "").trim(' ', ',', '(', ')')
        if (unit == "stick" && text.lowercase() in listOf("butter", "smør", "smor")) {
            packageQuantity = Quantity(113.3980925); packageUnit = "g"
        }
        if (unit != null && text.isBlank()) {
            // A unit without an ingredient name is ambiguous; keep the literal input.
            text = raw.trim(); quantity = null; unit = null
        }
        return Ingredient(raw = raw, quantity = quantity, unit = unit, name = text.ifBlank { raw.trim() }, note = note,
            optional = isOptional, scalable = scalable, category = Categories.suggest(text),
            packageQuantity = packageQuantity, packageUnit = packageUnit)
    }
}

object Categories {
    val defaults = listOf("produce", "bakery", "dairy", "meat", "seafood", "pantry", "frozen", "other")
    private val keywords = listOf(
        "produce" to listOf("onion", "tomato", "potato", "carrot", "garlic", "apple", "lemon", "parsley", "spinach", "løk", "tomat", "potet", "gulrot", "hvitløk", "eple", "sitron"),
        "dairy" to listOf("milk", "butter", "cheese", "cream", "yogurt", "egg", "melk", "smør", "ost", "fløte", "yoghurt"),
        "meat" to listOf("chicken", "beef", "pork", "bacon", "kylling", "kjøtt", "svin"),
        "seafood" to listOf("fish", "salmon", "shrimp", "cod", "fisk", "laks", "reke", "torsk"),
        "bakery" to listOf("bread", "baguette", "brød"),
        "frozen" to listOf("frozen", "frossen"),
        "pantry" to listOf("flour", "sugar", "rice", "pasta", "oil", "salt", "pepper", "mel", "sukker", "ris", "olje"),
    )
    fun suggest(name: String): String {
        val words = name.lowercase().split(Regex("[^\\p{L}]+"))
        return keywords.firstOrNull { (_, keys) -> words.any { word -> keys.any { word == it || word == it + "s" } } }?.first ?: "other"
    }
}
