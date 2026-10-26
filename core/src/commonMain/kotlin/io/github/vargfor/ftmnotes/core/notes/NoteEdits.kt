package io.github.vargfor.ftmnotes.core.notes

import io.github.vargfor.ftmnotes.core.model.NoteDocument

/** Apply only edited fields, preserving concurrent list actions and organisational changes. */
fun mergeNoteEdits(current: NoteDocument, before: NoteDocument, edited: NoteDocument): NoteDocument = current.copy(
    title = if (edited.title != before.title) edited.title else current.title,
    content = if (edited.content != before.content) edited.content else current.content,
    kind = if (edited.kind != before.kind) edited.kind else current.kind,
    items = if (edited.items != before.items) edited.items else current.items,
    attachments = if (edited.attachments != before.attachments) edited.attachments else current.attachments,
    pinned = if (edited.pinned != before.pinned) edited.pinned else current.pinned,
    archived = if (edited.archived != before.archived) edited.archived else current.archived,
    color = if (edited.color != before.color) edited.color else current.color,
    tags = if (edited.tags != before.tags) edited.tags else current.tags,
)
