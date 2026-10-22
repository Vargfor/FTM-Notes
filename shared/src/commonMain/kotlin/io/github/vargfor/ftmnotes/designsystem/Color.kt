package io.github.vargfor.ftmnotes.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Warm-neutral base palette shared by every section. Section accents (see [Section]) are layered
 * on top, so switching tabs only swaps the primary role colours.
 */
internal val BaseLight: ColorScheme = lightColorScheme(
    background = Color(0xFFFBF8F6),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFBF8F6),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFE8E2DD),
    onSurfaceVariant = Color(0xFF4B4743),
    surfaceDim = Color(0xFFDCD9D6),
    surfaceBright = Color(0xFFFBF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2F0),
    surfaceContainer = Color(0xFFF0ECEA),
    surfaceContainerHigh = Color(0xFFEAE7E4),
    surfaceContainerHighest = Color(0xFFE4E1DE),
    inverseSurface = Color(0xFF31302E),
    inverseOnSurface = Color(0xFFF3F0ED),
    outline = Color(0xFF7C7772),
    outlineVariant = Color(0xFFCDC6C0),
    secondary = Color(0xFF625D58),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E1DA),
    onSecondaryContainer = Color(0xFF1E1B17),
    tertiary = Color(0xFF6B5B8E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBDDFF),
    onTertiaryContainer = Color(0xFF251546),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

internal val BaseDark: ColorScheme = darkColorScheme(
    background = Color(0xFF141312),
    onBackground = Color(0xFFE6E2DF),
    surface = Color(0xFF141312),
    onSurface = Color(0xFFE6E2DF),
    surfaceVariant = Color(0xFF4B4743),
    onSurfaceVariant = Color(0xFFCDC6C0),
    surfaceDim = Color(0xFF141312),
    surfaceBright = Color(0xFF3B3937),
    surfaceContainerLowest = Color(0xFF0F0E0D),
    surfaceContainerLow = Color(0xFF1C1B1A),
    surfaceContainer = Color(0xFF211F1E),
    surfaceContainerHigh = Color(0xFF2B2A28),
    surfaceContainerHighest = Color(0xFF363433),
    inverseSurface = Color(0xFFE6E2DF),
    inverseOnSurface = Color(0xFF31302E),
    outline = Color(0xFF97918B),
    outlineVariant = Color(0xFF4B4743),
    secondary = Color(0xFFCDC5BE),
    onSecondary = Color(0xFF34302B),
    secondaryContainer = Color(0xFF4B4641),
    onSecondaryContainer = Color(0xFFE9E1DA),
    tertiary = Color(0xFFD3BCFD),
    onTertiary = Color(0xFF3B2A5C),
    tertiaryContainer = Color(0xFF524374),
    onTertiaryContainer = Color(0xFFEBDDFF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    scrim = Color(0xFF000000),
)

/** The four primary-role colours a section overrides, for one brightness. */
internal data class AccentTones(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

internal data class SectionAccent(val light: AccentTones, val dark: AccentTones)

internal val TrainingAccent = SectionAccent(
    light = AccentTones(Color(0xFFA6400F), Color(0xFFFFFFFF), Color(0xFFFFDBCC), Color(0xFF380D00)),
    dark = AccentTones(Color(0xFFFFB597), Color(0xFF5A1B00), Color(0xFF822F00), Color(0xFFFFDBCC)),
)

internal val FoodAccent = SectionAccent(
    light = AccentTones(Color(0xFF2B6A3F), Color(0xFFFFFFFF), Color(0xFFB1F1BC), Color(0xFF00210C)),
    dark = AccentTones(Color(0xFF95D5A1), Color(0xFF00391A), Color(0xFF105229), Color(0xFFB1F1BC)),
)

internal val NotesAccent = SectionAccent(
    light = AccentTones(Color(0xFF3B5F92), Color(0xFFFFFFFF), Color(0xFFD5E3FF), Color(0xFF001B3C)),
    dark = AccentTones(Color(0xFFA7C8FF), Color(0xFF02315F), Color(0xFF214777), Color(0xFFD5E3FF)),
)
