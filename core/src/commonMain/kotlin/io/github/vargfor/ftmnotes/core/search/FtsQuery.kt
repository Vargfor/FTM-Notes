package io.github.vargfor.ftmnotes.core.search

/**
 * Turns free text typed by the user into a safe SQLite FTS5 `MATCH` expression.
 *
 * Every word is quoted (so `-`, `AND`, `:` or stray quotes can't become query syntax) and
 * prefix-matched (`"bana"*` finds "banana"). Words are ANDed. Words without any letter or digit
 * are dropped, since the tokenizer ignores them anyway.
 */
object FtsQuery {
    private val whitespace = Regex("\\s+")

    /** @return the MATCH expression, or null when there is nothing searchable. */
    fun build(input: String): String? {
        val terms = input.trim()
            .split(whitespace)
            .filter { token -> token.any { it.isLetterOrDigit() } }
            .map { token -> "\"" + token.replace("\"", "\"\"") + "\"*" }
        return terms.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
