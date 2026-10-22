package io.github.vargfor.ftmnotes.core.backup

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.openZip
import okio.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ZipWriterTest {

    private val modified = Instant.parse("2026-10-22T18:30:42Z")

    @Test
    fun crc32MatchesStandardTestVectors() {
        assertEquals(0, Crc32.of(ByteArray(0)))
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0x414FA339, Crc32.of("The quick brown fox jumps over the lazy dog".encodeToByteArray()))
    }

    @Test
    fun okioReadsBackEverythingWeWrite() {
        val text = ("Shopping list: milk, eggs, flour. ".repeat(200)).encodeToByteArray() // compressible
        val photo = Random(42).nextBytes(50_000) // incompressible, like a JPEG
        val files = linkedMapOf(
            "manifest.json" to """{"format":1}""".encodeToByteArray(),
            "documents.ftm.json" to text,
            "attachments/ab12cd" to photo,
            "notes/blåbær-æøå.txt" to "UTF-8 name".encodeToByteArray(),
            "empty.txt" to ByteArray(0),
        )

        val fs = FakeFileSystem()
        val zipPath = "/backup.zip".toPath()
        ZipWriter(fs.sink(zipPath).buffer()).use { zip ->
            files.forEach { (name, bytes) -> zip.add(name, bytes, modified, compress = !name.startsWith("attachments/")) }
        }

        val zipFs: FileSystem = fs.openZip(zipPath)
        for ((name, bytes) in files) {
            val read = zipFs.read("/$name".toPath()) { readByteArray() }
            assertContentEquals(bytes, read, "content mismatch for $name")
        }
        assertEquals(
            setOf("manifest.json", "documents.ftm.json", "attachments", "notes", "empty.txt"),
            zipFs.list("/".toPath()).map { it.name }.toSet(),
        )
        // Compression actually happened for the text entry.
        assertTrue(fs.metadata(zipPath).size!! < text.size + photo.size, "archive should be smaller than its content")
    }

    @Test
    fun emptyArchiveIsValid() {
        val fs = FakeFileSystem()
        val zipPath = "/empty.zip".toPath()
        ZipWriter(fs.sink(zipPath).buffer()).close()
        assertEquals(emptyList(), fs.openZip(zipPath).list("/".toPath()))
    }
}
