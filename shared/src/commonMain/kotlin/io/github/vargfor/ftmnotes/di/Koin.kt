package io.github.vargfor.ftmnotes.di

import io.github.vargfor.ftmnotes.data.DeviceClock
import io.github.vargfor.ftmnotes.data.AttachmentStore
import io.github.vargfor.ftmnotes.data.ImportCoordinator
import io.github.vargfor.ftmnotes.data.BackupService
import io.github.vargfor.ftmnotes.data.RecipeImportService
import io.ktor.client.HttpClient
import io.github.vargfor.ftmnotes.platform.PlatformBridge
import io.github.vargfor.ftmnotes.platform.UnsupportedPlatformBridge
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.Transactor
import io.github.vargfor.ftmnotes.data.db.AppDatabase
import io.github.vargfor.ftmnotes.feature.notes.NoteEditorViewModel
import io.github.vargfor.ftmnotes.feature.notes.NotesViewModel
import io.github.vargfor.ftmnotes.feature.settings.SettingsViewModel
import io.github.vargfor.ftmnotes.feature.food.RecipeEditorViewModel
import io.github.vargfor.ftmnotes.feature.food.RecipeViewModel
import io.github.vargfor.ftmnotes.feature.food.ShoppingViewModel
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/** Provides the [AppDatabase] (and later other platform services) for each platform. */
expect val platformModule: Module

/** Outlives screens: used to finish writes (e.g. a final autosave) after a ViewModel is cleared. */
class AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)

val dataModule = module {
    single { AppPlatform(getOrNull<PlatformBridge>() ?: UnsupportedPlatformBridge) }
    single { AppScope() }
    single { get<AppDatabase>().documentDao() }
    single { get<AppDatabase>().importBatchDao() }
    single { get<AppDatabase>().attachmentDao() }
    single { get<AppDatabase>().devicePrefDao() }
    single { get<AppDatabase>().revisionDao() }
    single { AttachmentStore(getOrNull<PlatformBridge>() ?: UnsupportedPlatformBridge, get()) }
    single { ImportCoordinator(getOrNull<PlatformBridge>() ?: UnsupportedPlatformBridge, get(), get(), get(), get(), get()) }
    single { BackupService(getOrNull<PlatformBridge>() ?: UnsupportedPlatformBridge, get(), get(), get(), get(), get(), get(), get(), get()) }
    single { RecipeImportService(getOrNull<HttpClient>(), get(), get()) }
    single { Transactor(get()) }
    single { DeviceClock(get()) }
    single { DocumentRepository(get(), get(), get()) }
    // Eager: starts reading settings while the platform is still starting up.
    single(createdAtStart = true) { SettingsRepository(get(), get(), get(), get()) }
}

val viewModelModule = module {
    viewModelOf(::NotesViewModel)
    viewModelOf(::SettingsViewModel)
    viewModel { (noteId: String) -> NoteEditorViewModel(noteId, get(), get()) }
    viewModel { (id: String) -> RecipeEditorViewModel(id, get(), get()) }
    viewModel { (id: String) -> RecipeViewModel(id, get(), get(), get(), get()) }
    viewModel { (id: String) -> ShoppingViewModel(id, get(), get()) }
}

fun initKoin(config: KoinAppDeclaration = {}): KoinApplication = startKoin {
    config()
    modules(platformModule, dataModule, viewModelModule)
}

/**
 * Waits for settings to be read so the first frame already has the right theme and start tab.
 * It's a small database read; the timeout only guards against a stuck database.
 */
suspend fun awaitStartupSettings() {
    withTimeoutOrNull(2.seconds) { KoinPlatform.getKoin().get<SettingsRepository>().awaitLoaded() }
}
