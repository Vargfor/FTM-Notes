package io.github.vargfor.ftmnotes.core.model

/**
 * Note background colours. Names mirror Google Keep's so imports are lossless; "no colour"
 * is represented by `null` (Keep's `DEFAULT`). [wire] is the canonical value stored in
 * `FtmDocument.color`. [referenceRgb] is Keep's own swatch, used only to map arbitrary hex
 * values to the nearest palette entry; on-screen tones live in the design system.
 */
enum class NoteColor(val wire: String, val referenceRgb: Int) {
    Red("red", 0xFF6D3F),
    Orange("orange", 0xFF9B00),
    Yellow("yellow", 0xFFDA00),
    Green("green", 0x95D641),
    Teal("teal", 0x1CE8B5),
    Blue("blue", 0x3FC3FF),
    Cerulean("cerulean", 0x82B1FF),
    Purple("purple", 0xB388FF),
    Pink("pink", 0xF8BBD0),
    Brown("brown", 0xD7CCC8),
    Gray("gray", 0xB8C4C9);

    /** Outcome of [parse]: a palette colour, the default (no colour), or an unrecognised value. */
    sealed interface Parsed {
        data class Color(val color: NoteColor) : Parsed
        data object Default : Parsed
        data object Unknown : Parsed
    }

    companion object {
        private val defaultNames = setOf("", "default", "none", "white", "transparent")

        // Keep's current UI names for the same swatches.
        private val aliases = mapOf(
            "grey" to Gray, "coral" to Red, "peach" to Orange, "sand" to Yellow, "mint" to Green,
            "sage" to Teal, "fog" to Blue, "storm" to Cerulean, "dusk" to Purple, "blossom" to Pink,
            "clay" to Brown, "chalk" to Gray,
        )

        fun fromWire(value: String?): NoteColor? = value?.let { v -> entries.firstOrNull { it.wire == v } }

        /** Accepts any casing, Keep's UI names, "default", or a `#RRGGBB` hex (mapped to the nearest colour). */
        fun parse(value: String): Parsed {
            val v = value.trim().lowercase()
            if (v in defaultNames) return Parsed.Default
            (entries.firstOrNull { it.wire == v } ?: aliases[v])?.let { return Parsed.Color(it) }
            hexToRgb(v)?.let { rgb -> return Parsed.Color(nearest(rgb)) }
            return Parsed.Unknown
        }

        private fun hexToRgb(v: String): Int? {
            val hex = v.removePrefix("#")
            return if (hex.length == 6 && hex.all { it in '0'..'9' || it in 'a'..'f' }) hex.toInt(16) else null
        }

        private fun nearest(rgb: Int): NoteColor = entries.minBy { distance(it.referenceRgb, rgb) }

        private fun distance(a: Int, b: Int): Int {
            val dr = (a shr 16 and 0xff) - (b shr 16 and 0xff)
            val dg = (a shr 8 and 0xff) - (b shr 8 and 0xff)
            val db = (a and 0xff) - (b and 0xff)
            return dr * dr + dg * dg + db * db
        }
    }
}
