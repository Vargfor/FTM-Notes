package io.github.vargfor.ftmnotes.core.backup

import okio.Buffer
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Cross-checks our writer against an independent reader (java.util.zip), not just okio. */
class ZipWriterJdkCompatTest {

    @Test
    fun javaUtilZipReadsOurArchives() {
        val files = linkedMapOf(
            "a.json" to "{\"x\":1}".repeat(500).encodeToByteArray(),
            "photos/ø.jpg" to Random(7).nextBytes(10_000),
        )
        val buffer = Buffer()
        ZipWriter(buffer).use { zip ->
            files.forEach { (name, bytes) -> zip.add(name, bytes, Instant.parse("2026-10-22T12:00:00Z")) }
        }

        val read = linkedMapOf<String, ByteArray>()
        ZipInputStream(buffer.inputStream()).use { zis ->
            generateSequence { zis.nextEntry }.forEach { entry -> read[entry.name] = zis.readBytes() }
        }
        assertEquals(files.keys, read.keys)
        files.forEach { (name, bytes) -> assertContentEquals(bytes, read[name]) }
    }
}
