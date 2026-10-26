package io.github.vargfor.ftmnotes.di

import android.content.Context
import androidx.room3.Room
import io.github.vargfor.ftmnotes.data.db.AppDatabase
import io.github.vargfor.ftmnotes.data.db.buildFtmDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import io.github.vargfor.ftmnotes.platform.*
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.data.DocumentRepository
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual val platformModule: Module = module {
    single<PlatformBridge> { AndroidPlatformBridge(androidContext()) }
    single { HttpClient(OkHttp) }
    single<AppDatabase> {
        val context = androidContext()
        Room.databaseBuilder<AppDatabase>(context, context.getDatabasePath(AppDatabase.FILE_NAME).absolutePath)
            .buildFtmDatabase()
    }
}

/** Called once from the Android `Application`. */
fun initKoinAndroid(context: Context) {
    val koin = initKoin { androidContext(context.applicationContext) }.koin
    koin.get<AppScope>().launch {
        val settings = koin.get<SettingsRepository>()
        settings.awaitLoaded()
        koin.get<PlatformBridge>().backupsChanged(settings.snapshot.value[Flags.backupEnabled])
        val retention = settings.snapshot.value[Flags.trashRetentionDays]
        if (retention > 0) koin.get<DocumentRepository>().purgeExpiredTrash(retention.days)
    }
}
