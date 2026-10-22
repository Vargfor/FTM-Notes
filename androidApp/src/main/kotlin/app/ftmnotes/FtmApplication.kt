package app.ftmnotes

import android.app.Application
import app.ftmnotes.di.initKoinAndroid

class FtmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(this)
    }
}
