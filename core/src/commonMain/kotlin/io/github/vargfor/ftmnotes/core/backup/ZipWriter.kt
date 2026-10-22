package io.github.vargfor.ftmnotes.core.backup

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Buffer
import okio.BufferedSink
import okio.Closeable
import okio.Deflater
import okio.DeflaterSink
import okio.buffer
import okio.use
import kotlin.time.Instant

/**
 * Minimal ZIP writer (no ZIP64, no encryption) for our own backups; read back with okio's
 * `FileSystem.openZip`. Entries are buffered in memory one at a time so sizes and CRC are known
 * before the local header is written — fine for notes and phone photos.
 *
 * Limits: < 65,535 entries and < 4 GiB in total (checked).
 */
class ZipWriter(private val sink: BufferedSink) : Closeable {

    private class CentralEntry(
        val name: ByteArray,
        val method: Int,
        val dosTime: Int,
        val dosDate: Int,
        val crc: Int,
        val compressedSize: Long,
        val size: Long,
        val offset: Long,
    )

    private val entries = mutableListOf<CentralEntry>()
    private var offset = 0L
    private var closed = false

    /**
     * Adds a file. [compress] = false stores bytes as-is (use for already-compressed data such as
     * JPEG/PNG). Compression is also skipped automatically when it wouldn't save space.
     */
    fun add(name: String, data: ByteArray, modifiedAt: Instant, compress: Boolean = true) {
        check(!closed) { "ZipWriter is closed" }
        require(name.isNotEmpty() && !name.startsWith("/")) { "Invalid entry name: $name" }
        check(entries.size < MAX_ENTRIES) { "Too many entries for a non-ZIP64 archive" }

        val crc = Crc32.of(data)
        val deflated = if (compress && data.isNotEmpty()) deflate(data) else null
        val useDeflate = deflated != null && deflated.size < data.size
        val payload = if (useDeflate) deflated else data
        val method = if (useDeflate) METHOD_DEFLATED else METHOD_STORED
        val nameBytes = name.encodeToByteArray()
        val (dosTime, dosDate) = dosDateTime(modifiedAt)

        check(offset + payload.size + nameBytes.size + LOCAL_HEADER_SIZE < MAX_SIZE) { "Archive exceeds 4 GiB" }

        sink.writeIntLe(LOCAL_HEADER_SIGNATURE)
        sink.writeShortLe(VERSION_NEEDED)
        sink.writeShortLe(FLAG_UTF8)
        sink.writeShortLe(method)
        sink.writeShortLe(dosTime)
        sink.writeShortLe(dosDate)
        sink.writeIntLe(crc)
        sink.writeIntLe(payload.size)
        sink.writeIntLe(data.size)
        sink.writeShortLe(nameBytes.size)
        sink.writeShortLe(0) // extra field length
        sink.write(nameBytes)
        sink.write(payload)

        entries += CentralEntry(nameBytes, method, dosTime, dosDate, crc, payload.size.toLong(), data.size.toLong(), offset)
        offset += LOCAL_HEADER_SIZE + nameBytes.size + payload.size
    }

    /** Writes the central directory and end record, then closes the underlying sink. */
    override fun close() {
        if (closed) return
        closed = true
        val centralStart = offset
        for (e in entries) {
            sink.writeIntLe(CENTRAL_HEADER_SIGNATURE)
            sink.writeShortLe(VERSION_MADE_BY)
            sink.writeShortLe(VERSION_NEEDED)
            sink.writeShortLe(FLAG_UTF8)
            sink.writeShortLe(e.method)
            sink.writeShortLe(e.dosTime)
            sink.writeShortLe(e.dosDate)
            sink.writeIntLe(e.crc)
            sink.writeIntLe(e.compressedSize.toInt())
            sink.writeIntLe(e.size.toInt())
            sink.writeShortLe(e.name.size)
            sink.writeShortLe(0) // extra
            sink.writeShortLe(0) // comment
            sink.writeShortLe(0) // disk number start
            sink.writeShortLe(0) // internal attributes
            sink.writeIntLe(0) // external attributes
            sink.writeIntLe(e.offset.toInt())
            sink.write(e.name)
            offset += CENTRAL_HEADER_SIZE + e.name.size
        }
        val centralSize = offset - centralStart
        sink.writeIntLe(END_SIGNATURE)
        sink.writeShortLe(0) // this disk
        sink.writeShortLe(0) // disk with central directory
        sink.writeShortLe(entries.size)
        sink.writeShortLe(entries.size)
        sink.writeIntLe(centralSize.toInt())
        sink.writeIntLe(centralStart.toInt())
        sink.writeShortLe(0) // comment length
        sink.close()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = Buffer()
        DeflaterSink(out, Deflater(DEFLATE_LEVEL, true)).buffer().use { it.write(data) }
        return out.readByteArray()
    }

    private fun dosDateTime(instant: Instant): Pair<Int, Int> {
        val t = instant.toLocalDateTime(TimeZone.UTC)
        if (t.year < 1980) return 0 to ((1 shl 5) or 1) // 1980-01-01 00:00, the DOS epoch
        val time = (t.hour shl 11) or (t.minute shl 5) or (t.second / 2)
        val date = ((t.year - 1980) shl 9) or (t.month.ordinal + 1 shl 5) or t.day
        return time to date
    }

    private companion object {
        const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        const val END_SIGNATURE = 0x06054b50
        const val LOCAL_HEADER_SIZE = 30
        const val CENTRAL_HEADER_SIZE = 46
        const val VERSION_NEEDED = 20
        const val VERSION_MADE_BY = 20
        const val FLAG_UTF8 = 0x0800
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        const val DEFLATE_LEVEL = 6
        const val MAX_ENTRIES = 0xFFFF
        const val MAX_SIZE = 0xFFFFFFFFL
    }
}
