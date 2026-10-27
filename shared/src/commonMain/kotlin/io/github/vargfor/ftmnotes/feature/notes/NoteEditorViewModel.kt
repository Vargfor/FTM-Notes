package io.github.vargfor.ftmnotes.feature.notes

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mohamedrejeb.richeditor.model.RichTextState
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.notes.*
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.di.AppScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Loading rich text never rewrites the original. The final save outlives the screen. */
@OptIn(FlowPreview::class)
class NoteEditorViewModel(
    private val noteId: String,
    private val repository: DocumentRepository,
    private val appScope: AppScope,
) : ViewModel() {
    var draft by mutableStateOf(NoteDocument(id = noteId))
        private set
    val rich = RichTextState()
    var isLoaded by mutableStateOf(false)
        private set
    var isNew by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private val history = UndoStack<NoteDocument>()
    private val saveLock = Mutex()
    private var baseline = draft
    private var richBaseline = ""
    private var exists = false

    init {
        viewModelScope.launch {
            try {
                val note = repository.get(noteId)?.document as? NoteDocument
                exists = note != null
                isNew = !exists
                render(note?.copy(items = note.items.filterNot { it.deleted }) ?: draft)
                baseline = draft
                isLoaded = true
                launch {
                    snapshotFlow { rich.toMarkdown() }.collect { markdown ->
                        if (markdown != richBaseline && draft.kind == NoteKind.Text) {
                            richBaseline = markdown
                            edit(draft.copy(content = markdown), "body")
                        }
                    }
                }
                snapshotFlow { draft }.drop(1).debounce(300).collect { persist() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
        }
    }

    fun edit(note: NoteDocument, key: String? = null) {
        if (note == draft) return
        history.record(draft, key, Clock.System.now().toEpochMilliseconds())
        draft = note
        updateHistory()
    }

    fun changeKind() {
        val next = if (draft.kind == NoteKind.Text) {
            draft.copy(kind = NoteKind.Checklist, items = ChecklistOps.fromText(draft.content))
        } else draft.copy(kind = NoteKind.Text, content = ChecklistOps.toText(draft.items))
        edit(next)
        render(next)
        flush()
    }

    fun items(transform: (List<ChecklistItem>) -> List<ChecklistItem>, key: String? = null) {
        edit(draft.copy(items = transform(draft.items)), key)
        if (key == null) flush()
    }

    fun undo() { syncRich(); history.undo(draft)?.let(::render); updateHistory(); flush() }
    fun redo() { history.redo(draft)?.let(::render); updateHistory(); flush() }
    fun flush() { if (isLoaded) { syncRich(); appScope.launch { persist() } } }
    suspend fun flushNow(): Boolean { if (isLoaded) { syncRich(); persist() }; return error == null }
    override fun onCleared() = flush()

    private fun render(note: NoteDocument) {
        draft = note
        rich.setMarkdown(note.content)
        richBaseline = rich.toMarkdown()
    }

    private fun updateHistory() { canUndo = history.canUndo; canRedo = history.canRedo }

    private fun syncRich() {
        val markdown = rich.toMarkdown()
        if (draft.kind == NoteKind.Text && markdown != richBaseline) {
            richBaseline = markdown
            edit(draft.copy(content = markdown), "body")
        }
    }

    private suspend fun persist() = saveLock.withLock {
        val edited = draft.copy(title = draft.title.trim())
        if (edited == baseline) return@withLock
        if (!exists && edited.title.isBlank() && edited.content.isBlank() &&
            ChecklistOps.isBlank(edited.items) && edited.attachments.isEmpty()) return@withLock
        try {
            if (!exists) { repository.save(edited); exists = true }
            else repository.update(noteId) { current ->
                if (current is NoteDocument) mergeNoteEdits(current, baseline, edited) else current
            }
            baseline = edited
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
}
