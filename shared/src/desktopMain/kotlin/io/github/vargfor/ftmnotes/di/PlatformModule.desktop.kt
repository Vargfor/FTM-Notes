package io.github.vargfor.ftmnotes.di

import androidx.room3.Room
import io.github.vargfor.ftmnotes.data.db.AppDatabase
import io.github.vargfor.ftmnotes.data.db.buildFtmDatabase
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import io.github.vargfor.ftmnotes.platform.PlatformBridge
import io.github.vargfor.ftmnotes.platform.DesktopPlatformBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual val platformModule: Module = module {
    single<PlatformBridge> { DesktopPlatformBridge() }
    single { HttpClient(CIO) }
    single<AppDatabase> {
        // Dev-only target: keep data out of the repo, in the user's home directory (FTM_DEV_DIR overrides).
        val dir = (System.getenv("FTM_DEV_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".ftm-notes-dev")).apply { mkdirs() }
        Room.databaseBuilder<AppDatabase>(File(dir, AppDatabase.FILE_NAME).absolutePath)
            .buildFtmDatabase()
    }
}
