package io.github.vargfor.ftmnotes

import androidx.compose.runtime.Composable
import io.github.vargfor.ftmnotes.designsystem.FtmTheme
import io.github.vargfor.ftmnotes.navigation.AppShell

/** Entry point shared by every platform. Koin must be started before this is composed. */
@Composable
fun App() {
    FtmTheme {
        AppShell()
    }
}
