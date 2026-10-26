package io.github.vargfor.ftmnotes.feature.food

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.food.engine.*
import io.github.vargfor.ftmnotes.core.notes.UndoStack
import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.di.AppScope
import io.github.vargfor.ftmnotes.platform.AppPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@OptIn(FlowPreview::class)
class RecipeEditorViewModel(private val id: String, private val repository: DocumentRepository, private val appScope: AppScope) : ViewModel() {
    var draft by mutableStateOf(RecipeDocument(id = id))
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var baseline = draft
    private var exists = false
    private val history = UndoStack<RecipeDocument>()
    private val lock = Mutex()
    init {
        viewModelScope.launch {
            try {
                val stored = repository.get(id)?.document as? RecipeDocument
                exists = stored != null
                draft = stored ?: draft
                baseline = draft
                loaded = true
                snapshotFlow { draft }.drop(1).debounce(300).collect { persist() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    fun edit(value: RecipeDocument, key: String? = null) {
        if (draft == value) return
        history.record(draft, key, Clock.System.now().toEpochMilliseconds())
        draft = value
        canUndo = history.canUndo; canRedo = history.canRedo
        if (key == null) flush()
    }
    fun ingredient(group: String, id: String, transform: (Ingredient) -> Ingredient, key: String? = null) =
        edit(draft.copy(ingredientGroups = draft.ingredientGroups.map { g ->
            if (g.id != group) g else g.copy(items = g.items.map { if (it.id == id) transform(it) else it })
        }), key)
    fun undo() { history.undo(draft)?.let { draft = it }; canUndo = history.canUndo; canRedo = history.canRedo; flush() }
    fun redo() { history.redo(draft)?.let { draft = it }; canUndo = history.canUndo; canRedo = history.canRedo; flush() }
    fun flush() { if (loaded) appScope.launch { persist() } }
    suspend fun flushNow(): Boolean { if (loaded) persist(); return error == null }
    override fun onCleared() = flush()
    private suspend fun persist() = lock.withLock {
        val value = draft.copy(title = draft.title.trim())
        if (value == baseline) return@withLock
        val hasContent = value.title.isNotBlank() || value.description.isNotBlank() || value.notes.isNotBlank() || value.images.isNotEmpty() ||
            value.ingredientGroups.any { group -> group.items.any { it.name.isNotBlank() || it.raw.isNotBlank() } } || value.steps.any { it.text.isNotBlank() }
        if (!exists && !hasContent) return@withLock
        try {
            if (!exists) { repository.save(value); exists = true }
            else repository.update(id) { current -> if (current is RecipeDocument) mergeRecipeEdits(current, baseline, value) else current }
            baseline = value; error = null
        } catch (e: Exception) { error = e.message }
    }
}

@Serializable
data class RecipeViewState(
    val servings: Double? = null, val units: String? = null,
    val checkedIngredients: Set<String> = emptySet(), val checkedSteps: Set<String> = emptySet(),
    val currentStep: Int = 0, val timerEnds: Map<String, Long> = emptyMap(),
)

class RecipeViewModel(
    private val id: String, private val repository: DocumentRepository, private val prefs: DevicePrefDao,
    private val appScope: AppScope, private val platform: AppPlatform,
) : ViewModel() {
    var recipe by mutableStateOf<RecipeDocument?>(null)
        private set
    var state by mutableStateOf(RecipeViewState())
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    init {
        viewModelScope.launch {
            state = prefs.get("food.view.$id")?.let { runCatching { json.decodeFromString(RecipeViewState.serializer(), it) }.getOrNull() } ?: RecipeViewState()
            repository.observe(id).collect { stored -> recipe = stored?.document as? RecipeDocument; loaded = true }
        }
    }
    fun update(transform: (RecipeViewState) -> RecipeViewState) {
        state = transform(state)
        appScope.launch { persist() }
    }
    fun startTimer(key: String, seconds: Long) {
        require(seconds in 1..604800)
        val end = Clock.System.now().toEpochMilliseconds() + seconds * 1000
        update { it.copy(timerEnds = it.timerEnds + (key to end)) }
        appScope.launch {
            try { persist(); platform.bridge.scheduleTimer("$id/$key", recipe?.title.orEmpty(), end) }
            catch (e: Exception) { error = e.message }
        }
    }
    fun cancelTimer(key: String) {
        platform.bridge.cancelTimer("$id/$key")
        update { it.copy(timerEnds = it.timerEnds - key) }
    }
    fun flush() { appScope.launch { persist() } }
    fun clearError() { error = null }
    override fun onCleared() = flush()
    private suspend fun persist() = lock.withLock {
        prefs.put(DevicePrefEntity("food.view.$id", json.encodeToString(RecipeViewState.serializer(), state)))
    }
}
