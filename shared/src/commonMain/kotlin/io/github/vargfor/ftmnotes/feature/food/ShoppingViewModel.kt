package io.github.vargfor.ftmnotes.feature.food

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.food.engine.*
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.di.AppScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ShoppingViewModel(private val id: String, private val repository: DocumentRepository, private val appScope: AppScope) : ViewModel() {
    val list = repository.observe(id).map { it?.document as? ShoppingListDocument }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var error by mutableStateOf<String?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private data class Change(val before: ShoppingListDocument, val after: ShoppingListDocument)
    private val undo = ArrayDeque<Change>()
    private val redo = ArrayDeque<Change>()
    private val lock = Mutex()
    private val messages = Channel<Unit>(Channel.UNLIMITED)
    val updates = messages.receiveAsFlow()

    fun mutate(failed: (() -> Unit)? = null, transform: (ShoppingListDocument) -> ShoppingListDocument) {
        appScope.launch { lock.withLock {
            try {
                var change: Change? = null
                val written = repository.update(id) { current ->
                    if (current !is ShoppingListDocument) current else transform(current).also { if (it != current) change = Change(current, it) }
                }
                if (written && change != null) {
                    undo += change!!; if (undo.size > 200) undo.removeFirst(); redo.clear()
                    canUndo = true; canRedo = false; messages.send(Unit)
                }
                error = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message; withContext(Dispatchers.Main) { failed?.invoke() } }
        } }
    }
    fun add(text: String, failed: () -> Unit) {
        if (text.isBlank()) return
        mutate(failed) { it.copy(items = ShoppingLists.add(it.items, listOf(ShoppingLists.quickAdd(text)))) }
    }
    fun undo() = replay(false)
    fun redo() = replay(true)
    fun clearError() { error = null }
    private fun replay(forward: Boolean) {
        appScope.launch { lock.withLock {
            val from = if (forward) redo else undo
            val to = if (forward) undo else redo
            val change = from.removeLastOrNull() ?: return@withLock
            try {
                repository.update(id) { current ->
                    if (current !is ShoppingListDocument) current else if (forward)
                        ShoppingLists.undoMutation(change.after, change.before, current)
                    else ShoppingLists.undoMutation(change.before, change.after, current)
                }
                to += change; canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty()
            } catch (e: Exception) { from += change; error = e.message }
        } }
    }
}
