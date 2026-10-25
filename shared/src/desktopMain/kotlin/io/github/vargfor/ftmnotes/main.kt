package io.github.vargfor.ftmnotes

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.vargfor.ftmnotes.di.awaitStartupSettings
import io.github.vargfor.ftmnotes.di.initKoin
import kotlinx.coroutines.runBlocking

/** Dev-only desktop launcher for fast UI iteration (Compose Hot Reload). Phone-sized by default. */
fun main() {
    initKoin()
    runBlocking { awaitStartupSettings() }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "FTM Notes (dev)",
            state = rememberWindowState(width = 412.dp, height = 892.dp),
        ) {
            App()
        }
    }
}
