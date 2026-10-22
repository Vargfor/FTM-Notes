package io.github.vargfor.ftmnotes.core.model

import io.github.vargfor.ftmnotes.core.format.LenientInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/**
 * A document in the canonical FTM format. Type-specific fields sit at the same level as the
 * common ones (flat structure) because that is what LLMs reproduce most reliably.
 *
 * New document types are added as subclasses in their feature phase.
 */
@Serializable
sealed interface FtmDocument {
    /** Optional on import; the app generates one when missing. */
    val id: String?
    val title: String
    val tags: List<String>
    val pinned: Boolean
    val archived: Boolean
    /** Canonical [NoteColor.wire] name, or null for the default background. */
    val color: String?
    val createdAt: Instant?
    val updatedAt: Instant?

    /** Not serialized (no backing field); the JSON `"type"` discriminator carries it. */
    val documentType: DocumentType
}

@Serializable
@SerialName("note")
data class NoteDocument(
    override val id: String? = null,
    override val title: String = "",
    override val tags: List<String> = emptyList(),
    override val pinned: Boolean = false,
    override val archived: Boolean = false,
    override val color: String? = null,
    @Serializable(with = LenientInstantSerializer::class)
    override val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class)
    override val updatedAt: Instant? = null,
    val kind: NoteKind = NoteKind.Text,
    /**
     * Line-based Markdown (each newline is a line break; a blank line separates paragraphs):
     * `#`/`##` headings, `**bold**`, `*italic*`, `~~strike~~`, `<u>underline</u>`, `-`/`1.` lists,
     * `[links](url)` and `` `code` ``. No blockquotes or code blocks. Used when [kind] is text.
     */
    val content: String = "",
    /** Used when [kind] is checklist. */
    val items: List<ChecklistItem> = emptyList(),
    /** Images and other files attached to the note, stored by content hash. */
    val attachments: List<AttachmentRef> = emptyList(),
    /** Reserved for manual ordering (fractional index); not used yet. */
    val sortKey: String? = null,
) : FtmDocument {
    override val documentType: DocumentType get() = DocumentType.Note
}

@Serializable
enum class NoteKind {
    @SerialName("text") Text,
    @SerialName("checklist") Checklist,
}

@Serializable
data class ChecklistItem(
    val id: String? = null,
    val text: String = "",
    val checked: Boolean = false,
    val indent: Int = 0,
)

/** A file attached to a document. The bytes live in the attachment store under [sha256]. */
@Serializable
data class AttachmentRef(
    val sha256: String,
    val mimeType: String,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/**
 * App settings that follow the user across devices. There is exactly one, with id [ID].
 * Each value carries the HLC of its last change so devices can merge key by key.
 */
@Serializable
@SerialName("settings")
data class SettingsDocument(
    override val id: String? = ID,
    override val title: String = "Settings",
    override val tags: List<String> = emptyList(),
    override val pinned: Boolean = false,
    override val archived: Boolean = false,
    override val color: String? = null,
    @Serializable(with = LenientInstantSerializer::class)
    override val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class)
    override val updatedAt: Instant? = null,
    val values: Map<String, SettingValue> = emptyMap(),
) : FtmDocument {
    override val documentType: DocumentType get() = DocumentType.Settings

    companion object {
        const val ID = "ftm-settings"
    }
}

@Serializable
data class SettingValue(val v: JsonElement, val hlc: String)

/** Returns a copy with the storage-managed fields replaced. */
fun FtmDocument.withMeta(id: String, createdAt: Instant?, updatedAt: Instant?): FtmDocument = when (this) {
    is NoteDocument -> copy(id = id, createdAt = createdAt, updatedAt = updatedAt)
    is SettingsDocument -> copy(id = id, createdAt = createdAt, updatedAt = updatedAt)
}

/** Returns a copy with the organisational fields replaced (used by list-level actions). */
fun FtmDocument.withOrganisation(
    pinned: Boolean = this.pinned,
    archived: Boolean = this.archived,
    color: String? = this.color,
    tags: List<String> = this.tags,
): FtmDocument = when (this) {
    is NoteDocument -> copy(pinned = pinned, archived = archived, color = color, tags = tags)
    is SettingsDocument -> this
}
