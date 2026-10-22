package app.ftmnotes

import androidx.compose.runtime.Composable
import app.ftmnotes.designsystem.FtmTheme
import app.ftmnotes.navigation.AppShell

/** Entry point shared by every platform. Koin must be started before this is composed. */
@Composable
fun App() {
    FtmTheme {
        AppShell()
    }
}
