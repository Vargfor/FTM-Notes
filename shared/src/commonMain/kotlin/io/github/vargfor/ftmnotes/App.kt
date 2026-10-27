package io.github.vargfor.ftmnotes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.vargfor.ftmnotes.designsystem.components.LocalHelpIcons
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.core.settings.ThemeMode
import io.github.vargfor.ftmnotes.designsystem.FtmTheme
import io.github.vargfor.ftmnotes.navigation.AppShell
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import org.koin.compose.koinInject

/**
 * Entry point shared by every platform. Koin must be started, and settings loaded
 * ([SettingsRepository.awaitLoaded]), before this is composed.
 *
 * @param onThemeChange lets the platform follow the app theme (system bars, launch screen).
 */
@Composable
fun App(onThemeChange: (mode: ThemeMode, darkTheme: Boolean) -> Unit = { _, _ -> }) {
    val settings by koinInject<SettingsRepository>().snapshot.collectAsStateWithLifecycle()
    val themeMode = settings[Flags.theme]
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(themeMode, darkTheme) { onThemeChange(themeMode, darkTheme) }

    FtmTheme(darkTheme = darkTheme, pureBlack = settings[Flags.pureBlack]) {
        CompositionLocalProvider(LocalHelpIcons provides settings[Flags.helpIcons]) {
            AppShell(tabLayout = settings.tabLayout())
        }
    }
}
