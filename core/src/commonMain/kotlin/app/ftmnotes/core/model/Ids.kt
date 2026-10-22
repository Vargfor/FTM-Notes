package app.ftmnotes.core.model

import kotlin.uuid.Uuid

/** Single entry point for id generation so the underlying (experimental) UUID API is easy to swap. */
object Ids {
    /** Time-ordered UUIDv7: sorts by creation time, which keeps indexes and sync manifests tidy. */
    fun newId(): String = Uuid.generateV7().toString()

    /**
     * Deterministic id for seeded content (e.g. the built-in exercise library) so every device
     * generates the same id and sync never duplicates seed data.
     */
    fun seeded(namespace: String, slug: String): String =
        Uuid.fromByteArray(stableHash128("$namespace:$slug")).toString()

    // 128-bit FNV-1a variant split over two 64-bit lanes; only needs to be stable, not cryptographic.
    private fun stableHash128(input: String): ByteArray {
        var h1 = -0x340d631b7bdddcdbL // FNV offset basis
        var h2 = 0x6c62272e07bb0142L
        for (b in input.encodeToByteArray()) {
            h1 = (h1 xor (b.toLong() and 0xff)) * 0x100000001b3L
            h2 = (h2 xor (b.toLong() and 0xff)) * 0x100000001b3L xor (h1 ushr 29)
        }
        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            bytes[i] = (h1 ushr (56 - i * 8)).toByte()
            bytes[8 + i] = (h2 ushr (56 - i * 8)).toByte()
        }
        // Mark as RFC 4122 version 8 (custom) so seeded ids are recognisable.
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return bytes
    }
}
