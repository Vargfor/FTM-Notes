package io.github.vargfor.ftmnotes.di

import io.github.vargfor.ftmnotes.data.DeviceClock
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.db.AppDatabase
import io.github.vargfor.ftmnotes.feature.notes.NoteEditorViewModel
import io.github.vargfor.ftmnotes.feature.notes.NotesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** Provides the [AppDatabase] (and later other platform services) for each platform. */
expect val platformModule: Module

/** Outlives screens: used to finish writes (e.g. a final autosave) after a ViewModel is cleared. */
class AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)

val dataModule = module {
    single { AppScope() }
    single { get<AppDatabase>().documentDao() }
    single { get<AppDatabase>().devicePrefDao() }
    single { DeviceClock(get()) }
    single { DocumentRepository(get(), get()) }
}

val viewModelModule = module {
    viewModelOf(::NotesViewModel)
    viewModel { (noteId: String) -> NoteEditorViewModel(noteId, get(), get()) }
}

fun initKoin(config: KoinAppDeclaration = {}): KoinApplication = startKoin {
    config()
    modules(platformModule, dataModule, viewModelModule)
}
