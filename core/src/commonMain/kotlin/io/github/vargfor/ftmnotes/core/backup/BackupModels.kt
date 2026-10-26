package io.github.vargfor.ftmnotes.core.backup

import kotlinx.serialization.Serializable
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.format.FtmFormat
import kotlinx.serialization.json.JsonElement

/** Storage history is separate from content; tombstones do not need a decodable document. */
@Serializable
data class BackupRecord(
    val id: String, val type: String, val payloadJson: String,
    val createdAt: Long, val updatedAt: Long, val hlc: String,
    val deletedAt: Long? = null, val purged: Boolean = false,
)
@Serializable
data class BackupAttachment(val sha256: String, val mime: String, val size: Long)
@Serializable
data class BackupRevision(
    val id: String, val docId: String, val payloadJson: String, val hlc: String,
    val createdAt: Long, val deletedAt: Long? = null, val purged: Boolean = false,
)
@Serializable
data class BackupManifest(
    val format: Int = 1, val ftm: Int = FtmFormat.VERSION, val createdAt: Long,
    val records: List<BackupRecord>, val attachments: List<BackupAttachment> = emptyList(),
    val revisions: List<BackupRevision> = emptyList(), val preferences: Map<String, JsonElement> = emptyMap(),
)

fun FtmDocument.attachmentRefs(): List<AttachmentRef> = when (this) {
    is RecipeDocument -> images
    is ShoppingListDocument -> emptyList()
    is NoteDocument -> attachments
    is SettingsDocument -> emptyList()
}

/** Content identity excludes storage and organisation metadata; used only for import suggestions. */
fun FtmDocument.importFingerprint(): String {
    val doc = when (this) {
        is RecipeDocument -> copy(id = null, createdAt = null, updatedAt = null, pinned = false, archived = false, color = null, tags = emptyList(),
            ingredientGroups = ingredientGroups.map { it.copy(id = null, items = it.items.map { item -> item.copy(id = null) }) }, steps = steps.map { it.copy(id = null) })
        is ShoppingListDocument -> copy(id = null, createdAt = null, updatedAt = null, pinned = false, archived = false, color = null, tags = emptyList(),
            items = items.filterNot { it.deleted }.map { it.copy(id = null, modifiedHlc = null) })
        is NoteDocument -> copy(id = null, createdAt = null, updatedAt = null, pinned = false, archived = false, color = null, tags = emptyList(),
            items = items.filterNot { it.deleted }.map { it.copy(id = null, modifiedHlc = null) })
        is SettingsDocument -> this
    }
    return FtmFormat.encodeDocument(doc)
}
