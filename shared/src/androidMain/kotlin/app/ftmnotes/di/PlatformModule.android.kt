package app.ftmnotes.di

import android.content.Context
import androidx.room3.Room
import app.ftmnotes.data.db.AppDatabase
import app.ftmnotes.data.db.buildFtmDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<AppDatabase> {
        val context = androidContext()
        Room.databaseBuilder<AppDatabase>(context, context.getDatabasePath(AppDatabase.FILE_NAME).absolutePath)
            .buildFtmDatabase()
    }
}

/** Called once from the Android `Application`. */
fun initKoinAndroid(context: Context) {
    initKoin { androidContext(context.applicationContext) }
}
