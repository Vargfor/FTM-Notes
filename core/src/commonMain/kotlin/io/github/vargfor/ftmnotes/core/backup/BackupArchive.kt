package io.github.vargfor.ftmnotes.core.backup

import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.model.Hlc
import kotlinx.serialization.json.*
import okio.*
import okio.Path.Companion.toPath
import kotlin.time.Instant

/** Bounded, hash-verified ZIP transport. No files are written by read/validate. */
object BackupArchive {
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 0xffff_ffffL
    private val shaPattern = Regex("[0-9a-f]{64}")

    fun write(fs: FileSystem, path: Path, manifest: BackupManifest, attachment: (String) -> Source) {
        validate(manifest)
        val docs = manifest.records.filterNot { it.purged }.map { FtmFormat.writer.parseToJsonElement(it.payloadJson) }
        val envelope = buildJsonObject { put("ftm", FtmFormat.VERSION); put("documents", JsonArray(docs)) }
        val at = Instant.fromEpochMilliseconds(manifest.createdAt)
        fs.sink(path).buffer().use { sink ->
            ZipWriter(sink).use { zip ->
                zip.add("manifest.json", FtmFormat.writer.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray(), at)
                zip.add("documents.ftm.json", FtmFormat.writer.encodeToString(JsonObject.serializer(), envelope).encodeToByteArray(), at)
                for (blob in manifest.attachments) {
                    val bytes = attachment(blob.sha256).buffer().use { it.readLimited(MAX_ENTRY_BYTES) }
                    require(bytes.size.toLong() == blob.size && ByteString.of(*bytes).sha256().hex() == blob.sha256) { "Attachment checksum mismatch" }
                    zip.add("attachments/${blob.sha256}", bytes, at, compress = false)
                }
            }
        }
    }

    fun read(fs: FileSystem, path: Path): BackupManifest {
        val zip = fs.openZip(path)
        val files = zip.listRecursively("/".toPath()).filter { zip.metadata(it).isRegularFile }.toList()
        require(files.size <= 65_535) { "Too many backup entries" }
        require(files.sumOf { zip.metadata(it).size ?: 0 } <= MAX_ARCHIVE_BYTES) { "Backup is too large" }
        files.forEach { require(it.segments.none { p -> p == ".." } && it.toString().indexOf('\\') == -1) { "Invalid backup path" } }
        val manifest = FtmFormat.writer.decodeFromString(BackupManifest.serializer(),
            zip.source("/manifest.json".toPath()).buffer().use { it.readLimited(MAX_ENTRY_BYTES).decodeToString() })
        validate(manifest)
        val envelope = FtmFormat.writer.parseToJsonElement(zip.source("/documents.ftm.json".toPath()).buffer().use { it.readLimited(MAX_ENTRY_BYTES).decodeToString() }).jsonObject
        require(envelope["ftm"]?.jsonPrimitive?.int == FtmFormat.VERSION) { "Unsupported FTM version" }
        val docs = envelope["documents"]!!.jsonArray.map { it.jsonObject }.associateBy { requireNotNull(FtmFormat.decodeDocument(it.toString()).id) }
        require(docs.size == manifest.records.count { !it.purged }) { "Backup document count mismatch" }
        for (record in manifest.records.filterNot { it.purged }) {
            require(docs[record.id] == FtmFormat.writer.parseToJsonElement(record.payloadJson)) { "Backup document mismatch" }
        }
        for (blob in manifest.attachments) {
            val entry = "/attachments/${blob.sha256}".toPath()
            require(zip.metadata(entry).size == blob.size) { "Backup attachment size mismatch" }
            val hashing = HashingSource.sha256(zip.source(entry))
            hashing.buffer().use { input ->
                var total = 0L
                val buffer = Buffer()
                while (true) {
                    val count = input.read(buffer, 64 * 1024L)
                    if (count == -1L) break
                    total += count; require(total <= MAX_ENTRY_BYTES) { "Attachment is too large" }; buffer.clear()
                }
            }
            require(hashing.hash.hex() == blob.sha256) { "Backup attachment checksum mismatch" }
        }
        val expected = setOf("/manifest.json", "/documents.ftm.json") + manifest.attachments.map { "/attachments/${it.sha256}" }
        require(files.all { it.toString() in expected }) { "Unexpected files in backup" }
        return manifest
    }

    fun attachmentSource(fs: FileSystem, archive: Path, sha: String): Source {
        require(shaPattern.matches(sha))
        return fs.openZip(archive).source("/attachments/$sha".toPath())
    }

    private fun validate(manifest: BackupManifest) {
        require(manifest.format == 1 && manifest.ftm == FtmFormat.VERSION) { "Unsupported backup version" }
        require(manifest.records.map { it.id }.distinct().size == manifest.records.size) { "Duplicate backup document IDs" }
        require(manifest.attachments.map { it.sha256 }.distinct().size == manifest.attachments.size) { "Duplicate attachment IDs" }
        manifest.records.forEach { r ->
            require(r.id.isNotBlank()) { "Invalid backup document metadata" }
            Hlc.parse(r.hlc)
            if (!r.purged) {
                val doc = FtmFormat.decodeDocument(r.payloadJson)
                require(doc.id == r.id && doc.documentType.wire == r.type) { "Backup identity mismatch" }
                require(doc.createdAt == null || doc.createdAt?.toEpochMilliseconds() == r.createdAt) { "Backup creation time mismatch" }
                require(doc.updatedAt == null || doc.updatedAt?.toEpochMilliseconds() == r.updatedAt) { "Backup update time mismatch" }
            } else require(r.deletedAt != null) { "A tombstone must be deleted" }
        }
        manifest.revisions.forEach { Hlc.parse(it.hlc); if (!it.purged) FtmFormat.decodeDocument(it.payloadJson) }
        manifest.attachments.forEach { require(shaPattern.matches(it.sha256) && it.size in 0..MAX_ENTRY_BYTES) { "Invalid attachment metadata" } }
        val blobs = manifest.attachments.map { it.sha256 }.toSet()
        val payloads = manifest.records.filterNot { it.purged }.map { it.payloadJson } + manifest.revisions.filterNot { it.purged }.map { it.payloadJson }
        payloads.flatMap { FtmFormat.decodeDocument(it).attachmentRefs() }.forEach { require(it.sha256 in blobs) { "Missing attachment in backup" } }
    }
}

fun BufferedSource.readLimited(limit: Long): ByteArray {
    val result = Buffer()
    var total = 0L
    while (true) {
        val read = read(result, minOf(64 * 1024L, limit + 1 - total))
        if (read == -1L) break
        total += read
        require(total <= limit) { "File exceeds the supported size" }
    }
    return result.readByteArray()
}
