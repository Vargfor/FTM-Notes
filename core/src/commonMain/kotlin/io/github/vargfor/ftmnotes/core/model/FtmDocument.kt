package io.github.vargfor.ftmnotes.core.model

import io.github.vargfor.ftmnotes.core.format.LenientInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    /** Markdown subset (headings, bold, italic, strikethrough, `<u>`, lists, links, quotes). Used when [kind] is text. */
    val content: String = "",
    /** Used when [kind] is checklist. */
    val items: List<ChecklistItem> = emptyList(),
) : FtmDocument {
    override val documentType: DocumentType get() = DocumentType.Note
}

/** Returns a copy with the storage-managed fields replaced. */
fun FtmDocument.withMeta(id: String, createdAt: Instant?, updatedAt: Instant?): FtmDocument = when (this) {
    is NoteDocument -> copy(id = id, createdAt = createdAt, updatedAt = updatedAt)
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
