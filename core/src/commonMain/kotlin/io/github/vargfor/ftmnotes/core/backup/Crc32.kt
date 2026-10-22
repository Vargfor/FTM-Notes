package io.github.vargfor.ftmnotes.core.backup

/** CRC-32 (IEEE 802.3, as used by ZIP). Okio's implementation is internal, so we carry our own. */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(data: ByteArray): Int = update(0, data)

    fun update(crc: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var c = crc.inv()
        for (i in offset until offset + length) {
            c = table[(c xor data[i].toInt()) and 0xff] xor (c ushr 8)
        }
        return c.inv()
    }
}
