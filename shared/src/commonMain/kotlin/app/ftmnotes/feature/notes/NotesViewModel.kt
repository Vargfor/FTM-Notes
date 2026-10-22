package app.ftmnotes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ftmnotes.core.format.plainText
import app.ftmnotes.core.model.DocumentType
import app.ftmnotes.data.DocumentRepository
import app.ftmnotes.data.StoredDocument
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class NoteListItem(
    val id: String,
    val title: String,
    val snippet: String,
    val pinned: Boolean,
)

sealed interface NotesUiState {
    data object Loading : NotesUiState
    data class Ready(val notes: List<NoteListItem>) : NotesUiState
}

class NotesViewModel(repository: DocumentRepository) : ViewModel() {

    val state: StateFlow<NotesUiState> = repository.observeActive(DocumentType.Note)
        .map { documents -> NotesUiState.Ready(documents.map { it.toListItem() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState.Loading)

    private fun StoredDocument.toListItem() = NoteListItem(
        id = id,
        title = document.title,
        snippet = document.plainText().take(SnippetLength),
        pinned = document.pinned,
    )

    private companion object {
        const val SnippetLength = 280
    }
}
