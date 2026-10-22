package io.github.vargfor.ftmnotes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.db.DocumentListRow
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

    val state: StateFlow<NotesUiState> = repository.observeList(DocumentType.Note, archived = false)
        .map { rows -> NotesUiState.Ready(rows.map { it.toListItem() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState.Loading)

    private fun DocumentListRow.toListItem() = NoteListItem(
        id = id,
        title = title,
        snippet = preview,
        pinned = pinned,
    )
}
