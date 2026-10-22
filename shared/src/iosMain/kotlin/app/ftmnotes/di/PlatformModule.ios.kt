package app.ftmnotes.di

import androidx.room3.Room
import app.ftmnotes.data.db.AppDatabase
import app.ftmnotes.data.db.buildFtmDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual val platformModule: Module = module {
    single<AppDatabase> {
        Room.databaseBuilder<AppDatabase>(name = "${documentDirectory()}/${AppDatabase.FILE_NAME}")
            .buildFtmDatabase()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(url?.path) { "Documents directory unavailable" }
}
