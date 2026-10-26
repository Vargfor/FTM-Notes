package io.github.vargfor.ftmnotes.core.backup

import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.SettingsMerge

data class MergeResult(val record: BackupRecord, val conflictCopy: NoteDocument? = null, val revision: BackupRecord? = null)

/** Pure, deterministic policies shared by restore and the future sync transport. */
object DocumentMerger {
    fun merge(local: BackupRecord?, incoming: BackupRecord, conflictSuffix: String = " (restored copy)"): MergeResult {
        if (local == null) return MergeResult(incoming)
        require(local.id == incoming.id && local.type == incoming.type) { "Conflicting document identity" }
        if (local == incoming) return MergeResult(local)
        val winner = if (incoming.hlc > local.hlc) incoming else local
        val loser = if (winner === incoming) local else incoming
        if (local.purged || incoming.purged || local.deletedAt != null || incoming.deletedAt != null) {
            return MergeResult(winner, revision = loser.takeUnless { it.purged || it.payloadJson == winner.payloadJson })
        }
        val ours = FtmFormat.decodeDocument(local.payloadJson)
        val theirs = FtmFormat.decodeDocument(incoming.payloadJson)
        if (ours is ShoppingListDocument && theirs is ShoppingListDocument) {
            val latest = if (winner === incoming) theirs else ours
            val a = ours.items.associateBy { it.id }; val b = theirs.items.associateBy { it.id }
            val items = (latest.items.map { it.id } + a.keys + b.keys).distinct().map { id ->
                val x = a[id]; val y = b[id]
                when { x == null -> y!!; y == null -> x
                    (y.modifiedHlc ?: incoming.hlc) > (x.modifiedHlc ?: local.hlc) -> y; else -> x }
            }
            return MergeResult(winner.copy(payloadJson = FtmFormat.encodeDocument(latest.copy(items = items))))
        }
        if (ours is SettingsDocument && theirs is SettingsDocument) {
            return MergeResult(winner.copy(payloadJson = FtmFormat.encodeDocument(SettingsMerge.merge(ours, theirs))))
        }
        if (ours is NoteDocument && theirs is NoteDocument && ours.kind == NoteKind.Checklist && theirs.kind == NoteKind.Checklist) {
            val latest = if (winner === incoming) theirs else ours
            val items = mergeChecklist(ours.items, local.hlc, theirs.items, incoming.hlc, latest.items)
            return MergeResult(winner.copy(payloadJson = FtmFormat.encodeDocument(latest.copy(items = items))))
        }
        if (ours is NoteDocument && theirs is NoteDocument && ours.kind == NoteKind.Text && theirs.kind == NoteKind.Text) {
            if (ours.title == theirs.title && ours.content == theirs.content && ours.attachments == theirs.attachments) return MergeResult(winner)
            val other = if (winner === incoming) ours else theirs
            return MergeResult(winner, conflictCopy = other.copy(id = null, title = other.title + conflictSuffix, pinned = false))
        }
        return MergeResult(winner, revision = loser.takeUnless { it.payloadJson == winner.payloadJson })
    }

    private fun mergeChecklist(
        ours: List<ChecklistItem>, ourClock: String, theirs: List<ChecklistItem>, theirClock: String,
        order: List<ChecklistItem>,
    ): List<ChecklistItem> {
        fun keyed(items: List<ChecklistItem>) = items.mapIndexed { i, item -> item.copy(id = item.id ?: "legacy-$i") }.associateBy { it.id!! }
        val a = keyed(ours); val b = keyed(theirs)
        val keys = (keyed(order).keys + a.keys + b.keys).distinct()
        return keys.map { id ->
            val x = a[id]; val y = b[id]
            when {
                x == null -> y!!
                y == null -> x
                (y.modifiedHlc ?: theirClock) > (x.modifiedHlc ?: ourClock) -> y
                else -> x
            }
        }
    }
}

/** Stamp changed list items and retain deletions; empty clocks on legacy documents inherit the document clock. */
fun stampChecklistEdits(before: NoteDocument?, edited: NoteDocument, hlc: String): NoteDocument {
    if (edited.kind != NoteKind.Checklist) return edited
    val old = before?.items.orEmpty().associateBy { it.id }
    val activeOld = before?.items.orEmpty().filterNot { it.deleted }
    val ids = edited.items.map { it.id }.toSet()
    val changed = edited.items.mapIndexed { index, item ->
        val previous = old[item.id]
        val same = previous != null && previous.copy(modifiedHlc = null) == item.copy(modifiedHlc = null) &&
            (item.deleted || activeOld.indexOfFirst { it.id == item.id } == index)
        if (same) previous!!.copy(modifiedHlc = previous.modifiedHlc ?: hlc)
        else item.copy(modifiedHlc = hlc)
    }
    val removed = old.values.filter { it.id !in ids }.map { item ->
        if (item.deleted) item else item.copy(deleted = true, modifiedHlc = hlc)
    }
    return edited.copy(items = changed + removed)
}
