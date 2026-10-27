package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.core.notes.UndoStack
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.di.AppScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

@OptIn(FlowPreview::class)
class TrainingEditorViewModel(
    private val id: String, private val type: String, private val repository: DocumentRepository, private val appScope: AppScope,
) : ViewModel() {
    var draft by mutableStateOf<FtmDocument>(when (DocumentType.fromWire(type)) {
        DocumentType.Exercise -> ExerciseDocument(id = id)
        DocumentType.TrainingPlan -> TrainingPlanDocument(id = id)
        else -> WorkoutTemplateDocument(id = id)
    }); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private var baseline = draft
    private var exists = false
    private val lock = Mutex()
    private val history = UndoStack<FtmDocument>()
    init {
        viewModelScope.launch {
            try {
                val stored = repository.get(id)?.document
                exists = stored != null; draft = stored ?: draft; baseline = draft; loaded = true
                snapshotFlow { draft }.drop(1).debounce(300).collect { persist() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
        }
    }
    fun edit(document: FtmDocument, key: String? = null) {
        val value = document.normalisedTraining()
        if (draft == value) return
        history.record(draft, key, Clock.System.now().toEpochMilliseconds())
        draft = value; syncHistory()
        if (key == null) flush()
    }
    fun undo() { history.undo(draft)?.let { draft = it }; syncHistory(); flush() }
    fun redo() { history.redo(draft)?.let { draft = it }; syncHistory(); flush() }
    private fun syncHistory() { canUndo = history.canUndo; canRedo = history.canRedo }
    fun flush() { if (loaded) appScope.launch { persist() } }
    suspend fun flushNow(): Boolean { if (loaded) persist(); return loaded && error == null }
    override fun onCleared() = flush()
    private suspend fun persist() = lock.withLock {
        val value = draft
        if (value == baseline || !exists && value.title.isBlank()) return@withLock
        try {
            if (!exists) { repository.save(value); exists = true }
            else repository.update(id) { current -> mergeTrainingEdits(current, baseline, value) }
            baseline = value; error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
}

class WorkoutViewModel(
    val id: String, private val repository: DocumentRepository, val training: TrainingService, private val appScope: AppScope,
) : ViewModel() {
    var session by mutableStateOf<WorkoutSessionDocument?>(null); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private data class FieldKey(val exercise: String, val set: String, val field: String)
    private val pending = linkedMapOf<FieldKey, (PerformedSet) -> PerformedSet>()
    private var typing: Job? = null
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val undo = ArrayDeque<Pair<WorkoutSessionDocument, WorkoutSessionDocument>>()
    private val redo = ArrayDeque<Pair<WorkoutSessionDocument, WorkoutSessionDocument>>()
    init {
        appScope.launch {
            for (task in operations) {
                try { task(); error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
            }
        }
        viewModelScope.launch {
            repository.observe(id).collect { stored ->
                val value = stored?.document as? WorkoutSessionDocument
                withContext(Dispatchers.Main) { session = value?.let(::applyPending); loaded = true }
            }
        }
    }
    private fun applyPending(doc: WorkoutSessionDocument, entries: List<Pair<FieldKey, (PerformedSet) -> PerformedSet>> = pending.toList()) =
        doc.copy(exercises = doc.exercises.map { e -> e.copy(sets = e.sets.map { s ->
            entries.filter { it.first.exercise == e.id && it.first.set == s.id }.fold(s) { value, change -> change.second(value) }
        }) })
    fun edit(exercise: String, set: String, field: String, transform: (PerformedSet) -> PerformedSet) {
        pending.remove(FieldKey(exercise, set, field))
        pending[FieldKey(exercise, set, field)] = transform
        session = session?.let(::applyPending)
        typing?.cancel()
        typing = viewModelScope.launch { delay(300); flush() }
    }
    fun flush() { typing?.cancel(); operations.trySend { persistPending() } }
    suspend fun flushNow(): Boolean {
        typing?.cancel()
        val done = CompletableDeferred<Boolean>()
        operations.send {
            try { persistPending(); done.complete(true) }
            catch (e: Exception) { error = e.message; done.complete(false) }
        }
        return done.await()
    }
    private suspend fun persistPending() {
        val values = withContext(Dispatchers.Main) { pending.toList() }
        if (values.isEmpty()) return
        val (before, after) = training.atomic {
            val before = repository.get(id)?.document as? WorkoutSessionDocument
            repository.update(id) { current -> if (current is WorkoutSessionDocument) applyPending(current, values) else current }
            before to (repository.get(id)?.document as? WorkoutSessionDocument)
        }
        if (before != null && after != null && before != after) {
            undo.addLast(before to after); if (undo.size > 100) undo.removeFirst(); redo.clear()
            withContext(Dispatchers.Main) { canUndo = true; canRedo = false }
        }
        withContext(Dispatchers.Main) { values.forEach { (key, transform) -> if (pending[key] === transform) pending.remove(key) } }
    }
    fun action(block: suspend () -> Unit) {
        operations.trySend {
            persistPending()
            val (before, after) = training.atomic {
                val before = repository.get(id)?.document as? WorkoutSessionDocument
                block()
                before to (repository.get(id)?.document as? WorkoutSessionDocument)
            }
            if (before != null && after != null && before != after) {
                undo.addLast(before to after); if (undo.size > 100) undo.removeFirst(); redo.clear()
                withContext(Dispatchers.Main) { canUndo = true; canRedo = false }
            }
        }
    }
    fun undo() = replay(false)
    fun redo() = replay(true)
    private fun replay(forward: Boolean) {
        operations.trySend {
            persistPending()
            val from = if (forward) redo else undo
            val to = if (forward) undo else redo
            val value = from.removeLastOrNull() ?: return@trySend
            try {
                repository.update(id) { current -> mergeTrainingEdits(current,
                    if (forward) value.first else value.second, if (forward) value.second else value.first) }
                if (training.active.value?.session?.id == id) training.skipRest()
                to.addLast(value)
            } catch (e: Exception) { from.addLast(value); throw e }
            withContext(Dispatchers.Main) { canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty() }
        }
    }
    override fun onCleared() { flush(); operations.close() }
}
