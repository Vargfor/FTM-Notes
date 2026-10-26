package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.backup.BackupArchive
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.platform.PlatformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.*
import kotlin.time.Clock

class AttachmentStore(
    private val bridge: PlatformBridge, private val dao: AttachmentDao,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    private val root: Path get() = requireNotNull(bridge.filesRoot) { "File storage is unavailable" } / "attachments"
    fun path(sha: String): Path {
        require(Regex("[a-f0-9]{64}").matches(sha)) { "Invalid attachment reference" }
        return root / sha
    }
    suspend fun put(source: Source, mime: String, name: String? = null): AttachmentRef = withContext(Dispatchers.IO) {
        fs.createDirectories(root)
        val staging = root / ".${Ids.newId()}.tmp"
        try {
            val hashing = HashingSink.sha256(fs.sink(staging))
            var total = 0L
            source.buffer().use { input ->
                hashing.buffer().use { output ->
                    val buffer = Buffer()
                    while (true) {
                        val read = input.read(buffer, 64 * 1024L)
                        if (read == -1L) break
                        total += read
                        require(total <= BackupArchive.MAX_ENTRY_BYTES) { "Attachment is too large" }
                        output.write(buffer, read)
                    }
                }
            }
            val sha = hashing.hash.hex()
            val target = path(sha)
            if (!fs.exists(target)) fs.atomicMove(staging, target)
            else {
                if (!verify(sha)) { fs.delete(target); fs.atomicMove(staging, target) }
                else fs.delete(staging)
            }
            dao.insert(AttachmentEntity(sha, mime, total, Clock.System.now().toEpochMilliseconds()))
            AttachmentRef(sha, mime, name)
        } finally { fs.delete(staging, mustExist = false) }
    }
    fun source(sha: String): Source = fs.source(path(sha))
    suspend fun metadata(sha: String): AttachmentEntity? = dao.get(sha)
    fun exists(sha: String): Boolean = fs.exists(path(sha))
    fun verify(sha: String): Boolean {
        if (!exists(sha)) return false
        val hashing = HashingSource.sha256(source(sha))
        hashing.buffer().use { input ->
            val buffer = Buffer()
            while (input.read(buffer, 64 * 1024L) != -1L) buffer.clear()
        }
        return hashing.hash.hex() == sha
    }
}
