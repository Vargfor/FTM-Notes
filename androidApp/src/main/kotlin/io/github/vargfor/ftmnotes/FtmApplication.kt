package io.github.vargfor.ftmnotes

import android.app.Application
import io.github.vargfor.ftmnotes.di.initKoinAndroid

class FtmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(this)
    }
}
