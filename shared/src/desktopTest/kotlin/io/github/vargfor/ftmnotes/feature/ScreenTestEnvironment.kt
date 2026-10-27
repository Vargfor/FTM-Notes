package io.github.vargfor.ftmnotes.feature

import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.di.AppScope
import io.github.vargfor.ftmnotes.di.viewModelModule
import io.github.vargfor.ftmnotes.platform.*
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import kotlinx.coroutines.*
import okio.Path.Companion.toPath
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

internal class ScreenTestEnvironment {
    val db = Room.inMemoryDatabaseBuilder<AppDatabase>().buildFtmDatabase()
    val transactor = Transactor(db)
    val clock = DeviceClock(db.documentDao())
    val repository = DocumentRepository(db.documentDao(), transactor, clock, training = db.trainingDao())
    val scope = AppScope()
    val bridge = RecordingBridge()
    val platform = AppPlatform(bridge)
    val settings = SettingsRepository(repository, db.devicePrefDao(), clock, scope)
    val training = TrainingService(repository, db.trainingDao(), db.documentDao(), db.devicePrefDao(), transactor, settings, platform, scope)
    val attachments = AttachmentStore(bridge, db.attachmentDao())
    val imports = ImportCoordinator(bridge, attachments, repository, db.documentDao(), db.importBatchDao(), transactor)
    val recipeImports = RecipeImportService(null, imports, attachments)
    val models = ViewModelStore()
    private var koinStarted = false

    suspend fun ready() = withTimeout(10_000) { settings.awaitLoaded(); training.awaitReady() }

    fun startInjection() {
        startKoin { modules(module {
            single { repository }
            single { training }
            single { db.trainingDao() }
            single { transactor }
            single { scope }
            single { platform }
            single { settings }
            single { attachments }
            single { imports }
            single { recipeImports }
            single { db.devicePrefDao() }
        }, viewModelModule) }
        koinStarted = true
    }

    suspend fun <T : ViewModel> model(key: String, create: () -> T): T = withContext(Dispatchers.Main) {
        create().also { models.put(key, it) }
    }
    suspend fun onMain(action: () -> Unit) = withContext(Dispatchers.Main) {
        action()
        Snapshot.sendApplyNotifications()
    }
    suspend fun await(predicate: suspend () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) {
            withContext(Dispatchers.Main) { Snapshot.sendApplyNotifications() }
            delay(10)
        }
    }
    suspend fun close() {
        withContext(Dispatchers.Main) { models.clear() }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        if (koinStarted) stopKoin()
        db.close()
        bridge.directory.toFile().deleteRecursively()
    }

    suspend fun seed() {
        ready()
        val date = Instant.parse("2026-10-22T10:00:00Z")
        repository.save(NoteDocument(id = "note", title = "Weekend notes",
            content = "## A little breathing room\nRead a book, take a walk, and cook something new.",
            tags = listOf("Personal"), pinned = true, color = "yellow", createdAt = date))
        repository.save(NoteDocument(id = "checklist", title = "Before the trip", kind = NoteKind.Checklist,
            items = listOf(ChecklistItem("passport", "Passport", checked = true), ChecklistItem("charger", "Phone charger")),
            tags = listOf("Travel"), createdAt = date))
        repository.save(RecipeDocument(id = "recipe", title = "Lemon chicken",
            description = "A simple dinner with bright lemon and fresh herbs.", servings = Servings(4.0, "portions"),
            prepMinutes = 15, cookMinutes = 25, totalMinutes = 40,
            ingredientGroups = listOf(IngredientGroup("main", "For the pan", listOf(
                Ingredient("chicken", raw = "500 g chicken", name = "chicken", quantity = Quantity(500.0), unit = "g"),
                Ingredient("oil", raw = "2 tbsp olive oil", name = "olive oil", quantity = Quantity(2.0), unit = "us_tbsp")))),
            steps = listOf(RecipeStep("prep", "Warm the pan, then add the chicken and olive oil."),
                RecipeStep("cook", "Cook at 350°F for 20 minutes.", timerSeconds = 1200)), createdAt = date))
        repository.save(ShoppingListDocument(id = "shopping", title = "This week's groceries",
            items = listOf(ShoppingItem("milk", "Milk", Quantity(1.0), "l", "dairy"),
                ShoppingItem("lemons", "Lemons", Quantity(2.0), "piece", "produce"),
                ShoppingItem("bread", "Bread", Quantity(1.0), "piece", "bakery", checked = true)), createdAt = date))
    }
}

internal class RecordingBridge : PlatformBridge {
    val directory = Files.createTempDirectory("ftm-screen-test-")
    override val filesRoot = directory.toString().toPath()
    override val cacheRoot = filesRoot / "cache"
    val timers = ConcurrentHashMap<String, Long>()
    var timerFailure: Exception? = null
    var copiedText: String? = null
    var awake = false
    override fun keepAwake(enabled: Boolean) { awake = enabled }
    override suspend fun scheduleTimer(id: String, title: String, endMillis: Long) {
        timerFailure?.let { throw it }
        timers[id] = endMillis
    }
    override fun cancelTimer(id: String) { timers.remove(id) }
    override suspend fun copyText(text: String) { copiedText = text }
    override suspend fun shareText(text: String) { copiedText = text }
}
