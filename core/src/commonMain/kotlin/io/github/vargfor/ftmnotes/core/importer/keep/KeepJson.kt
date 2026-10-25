package io.github.vargfor.ftmnotes.core.importer.keep

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One note file in a Google Keep Takeout export (`Takeout/Keep/<title>.json`). */
@Serializable
internal data class KeepNoteJson(
    val title: String = "",
    val textContent: String? = null,
    /** Newer exports only: the same text with formatting. */
    val textContentHtml: String? = null,
    val listContent: List<KeepListItemJson>? = null,
    val color: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val labels: List<KeepLabelJson> = emptyList(),
    val annotations: List<KeepAnnotationJson> = emptyList(),
    val attachments: List<KeepAttachmentJson> = emptyList(),
    val createdTimestampUsec: Long? = null,
    val userEditedTimestampUsec: Long? = null,
)

@Serializable
internal data class KeepListItemJson(val text: String = "", val isChecked: Boolean = false)

@Serializable
internal data class KeepLabelJson(val name: String = "")

@Serializable
internal data class KeepAnnotationJson(
    val source: String? = null,
    val url: String? = null,
    val title: String? = null,
    val description: String? = null,
)

@Serializable
internal data class KeepAttachmentJson(val filePath: String = "", val mimetype: String? = null)

internal object KeepJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Recognises Keep notes by their keys, not their folder: Takeout localises folder names and users re-zip. */
    fun looksLikeKeepNote(obj: JsonObject): Boolean =
        "userEditedTimestampUsec" in obj ||
            ("isTrashed" in obj && ("textContent" in obj || "listContent" in obj || "textContentHtml" in obj))
}
