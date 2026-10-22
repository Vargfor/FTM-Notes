package io.github.vargfor.ftmnotes.feature.notes

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.di.AppScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plain-text note editor with autosave. A new note (id not yet in the database) is only written
 * once it has content, so opening and closing "New note" never leaves empty notes behind.
 *
 * Phase 1 replaces the body field with the rich-text editor.
 */
@OptIn(FlowPreview::class)
class NoteEditorViewModel(
    private val noteId: String,
    private val repository: DocumentRepository,
    private val appScope: AppScope,
) : ViewModel() {

    val title = TextFieldState()
    val content = TextFieldState()

    var isLoaded by mutableStateOf(false)
        private set

    /** True when this editor was opened for a note that doesn't exist yet. */
    var isNew by mutableStateOf(false)
        private set

    private val saveLock = Mutex()
    private var existsInDb = false
    /** Last title/content written (or loaded): the baseline that decides whether anything changed. */
    private var baseline: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            val existing = repository.get(noteId)?.document as? NoteDocument
            existsInDb = existing != null
            isNew = existing == null
            existing?.let {
                title.setTextAndPlaceCursorAtEnd(it.title)
                content.setTextAndPlaceCursorAtEnd(it.content)
                baseline = it.title to it.content
            }
            isLoaded = true

            snapshotFlow { title.text.toString() to content.text.toString() }
                .drop(1) // the initial load isn't an edit
                .debounce(AutosaveDebounceMillis)
                .collect { (title, content) -> persist(title, content) }
        }
    }

    override fun onCleared() {
        // Flush the latest text even if the debounce window hasn't elapsed.
        if (!isLoaded) return
        val latestTitle = title.text.toString()
        val latestContent = content.text.toString()
        appScope.launch { persist(latestTitle, latestContent) }
    }

    private suspend fun persist(title: String, content: String) = saveLock.withLock {
        val edited = title.trim() to content
        if (edited == baseline) return@withLock
        if (!existsInDb) {
            if (title.isBlank() && content.isBlank()) return@withLock
            repository.save(NoteDocument(id = noteId, title = edited.first, content = edited.second))
            existsInDb = true
        } else {
            // Read-modify-write on the stored version: keeps pin/colour/tags changed elsewhere.
            repository.update(noteId) { doc ->
                (doc as? NoteDocument)?.copy(title = edited.first, content = edited.second) ?: doc
            }
        }
        baseline = edited
    }

    private companion object {
        const val AutosaveDebounceMillis = 300L
    }
}
