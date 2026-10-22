package io.github.vargfor.ftmnotes.di

import androidx.room3.Room
import io.github.vargfor.ftmnotes.data.db.AppDatabase
import io.github.vargfor.ftmnotes.data.db.buildFtmDatabase
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

actual val platformModule: Module = module {
    single<AppDatabase> {
        // Dev-only target: keep data out of the repo, in the user's home directory.
        val dir = File(System.getProperty("user.home"), ".ftm-notes-dev").apply { mkdirs() }
        Room.databaseBuilder<AppDatabase>(File(dir, AppDatabase.FILE_NAME).absolutePath)
            .buildFtmDatabase()
    }
}
