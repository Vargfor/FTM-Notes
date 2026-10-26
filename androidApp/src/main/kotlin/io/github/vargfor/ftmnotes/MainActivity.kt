package io.github.vargfor.ftmnotes

import android.app.UiModeManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.content.Intent
import io.github.vargfor.ftmnotes.platform.androidPlatformBridge
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.vargfor.ftmnotes.core.settings.ThemeMode
import io.github.vargfor.ftmnotes.di.awaitStartupSettings
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        androidPlatformBridge().bind(this)
        if (savedInstanceState == null) androidPlatformBridge().receive(intent)
        // Settings were already being read since Application.onCreate; usually this doesn't wait at all.
        runBlocking { awaitStartupSettings() }

        setContent {
            App(onThemeChange = { mode, dark ->
                // The app theme can differ from the system's, so the bar icons follow the app.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { dark },
                )
                // The system remembers this and draws the next launch window in the same mode, so a
                // forced dark theme doesn't flash white on start. Handled as a config change (manifest).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(UiModeManager::class.java).setApplicationNightMode(
                        when (mode) {
                            ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                            ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                            ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
                        }
                    )
                }
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        androidPlatformBridge().receive(intent)
    }

    override fun onDestroy() {
        androidPlatformBridge().unbind(this)
        super.onDestroy()
    }

    private companion object {
        // The platform's default scrims for 3-button navigation (same as enableEdgeToEdge's).
        val LightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
