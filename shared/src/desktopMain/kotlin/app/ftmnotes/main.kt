package app.ftmnotes

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.ftmnotes.di.initKoin

/** Dev-only desktop launcher for fast UI iteration (Compose Hot Reload). Phone-sized by default. */
fun main() {
    initKoin()
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
