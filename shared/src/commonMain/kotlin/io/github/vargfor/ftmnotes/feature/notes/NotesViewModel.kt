package io.github.vargfor.ftmnotes.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.Transactor
import io.github.vargfor.ftmnotes.data.db.DocumentListRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

enum class NoteCollection { Active, Archive, Trash }
data class ColorChange(val value: String?)
data class ListMutation(
    val pinned: Boolean? = null, val archived: Boolean? = null, val color: ColorChange? = null,
    val tags: List<String>? = null, val trashed: Boolean? = null,
)
sealed interface NotesUiState {
    data object Loading : NotesUiState
    data class Ready(val notes: List<DocumentListRow>) : NotesUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(private val repository: DocumentRepository, private val transactor: Transactor) : ViewModel() {
    private val collection = MutableStateFlow(NoteCollection.Active)
    private val tag = MutableStateFlow<String?>(null)
    private val type = MutableStateFlow(DocumentType.Note)
    val state = combine(collection, tag, type) { mode, label, kind -> Triple(mode, label, kind) }.flatMapLatest { (mode, label, kind) ->
        if (mode == NoteCollection.Trash) repository.observeTrash(kind)
        else repository.observeList(kind, mode == NoteCollection.Archive, label)
    }.map { NotesUiState.Ready(it) as NotesUiState }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotesUiState.Loading)
    val tags = repository.observeTagCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun filter(mode: NoteCollection, label: String?, kind: DocumentType = DocumentType.Note) { collection.value = mode; tag.value = label; type.value = kind }

    suspend fun mutate(ids: Set<String>, mutation: ListMutation): suspend () -> Unit {
        val before = transactor.write {
            ids.mapNotNull { repository.get(it) }.also { documents ->
                documents.forEach { stored ->
                    repository.update(stored.id) { doc -> doc.withOrganisation(
                        pinned = mutation.pinned ?: doc.pinned, archived = mutation.archived ?: doc.archived,
                        color = mutation.color?.value ?: if (mutation.color == null) doc.color else null,
                        tags = mutation.tags ?: doc.tags) }
                    if (mutation.trashed == true) repository.moveToTrash(stored.id)
                    if (mutation.trashed == false) repository.restore(stored.id)
                }
            }
        }
        return {
            transactor.write {
                before.forEach { stored ->
                    repository.update(stored.id) { doc -> doc.withOrganisation(
                        pinned = if (mutation.pinned != null) stored.document.pinned else doc.pinned,
                        archived = if (mutation.archived != null) stored.document.archived else doc.archived,
                        color = if (mutation.color != null) stored.document.color else doc.color,
                        tags = if (mutation.tags != null) stored.document.tags else doc.tags) }
                    if (mutation.trashed != null) {
                        if (stored.deletedAt == null) repository.restore(stored.id) else repository.moveToTrash(stored.id)
                    }
                }
            }
        }
    }
    suspend fun purge(ids: Set<String>) = transactor.write { ids.forEach { repository.deleteForever(it) } }
    suspend fun renameTag(from: String, to: String) = repository.renameTag(from, to)
    suspend fun deleteTag(tag: String) = repository.deleteTag(tag)
    suspend fun duplicate(id: String, title: (String) -> String) {
        val note = repository.get(id)?.document as? NoteDocument ?: return
        repository.save(note.copy(id = null, title = title(note.title), createdAt = null, updatedAt = null, pinned = false, archived = false))
    }
}
