package io.github.vargfor.ftmnotes.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** The three top-level areas of the app; each has its own accent colour. */
enum class Section { Training, Food, Notes }

private val FtmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * App-wide theme: warm-neutral base, expressive motion, rounded shapes.
 * [pureBlack] only applies in the dark theme (true-black backgrounds for OLED screens).
 */
@Composable
fun FtmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    section: Section = Section.Notes,
    content: @Composable () -> Unit,
) {
    val base = when {
        !darkTheme -> BaseLight
        pureBlack -> BasePureBlack
        else -> BaseDark
    }
    MaterialExpressiveTheme(
        colorScheme = base.withAccent(section.accent().tones(darkTheme)),
        motionScheme = MotionScheme.expressive(),
        shapes = FtmShapes,
        typography = Typography(),
        content = content,
    )
}

/**
 * Re-colours [content] with the accent of [section], animating the change so switching tabs
 * feels like moving between rooms rather than a hard cut.
 */
@Composable
fun SectionTheme(section: Section, content: @Composable () -> Unit) {
    val current = MaterialTheme.colorScheme
    val target = section.accent().tones(darkTheme = current.background.luminanceIsDark())
    val primary by animateColorAsState(target.primary, tween(AccentAnimationMillis))
    val onPrimary by animateColorAsState(target.onPrimary, tween(AccentAnimationMillis))
    val primaryContainer by animateColorAsState(target.primaryContainer, tween(AccentAnimationMillis))
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, tween(AccentAnimationMillis))
    // Nested expressive theme only overrides colours; motion, shapes and type are inherited.
    MaterialExpressiveTheme(
        colorScheme = current.withAccent(AccentTones(primary, onPrimary, primaryContainer, onPrimaryContainer)),
        content = content,
    )
}

/** Tabular (fixed-width) digits so weights, reps and quantities line up in columns. */
val TextStyle.tabularNumbers: TextStyle get() = copy(fontFeatureSettings = "tnum")

private const val AccentAnimationMillis = 350

private fun Section.accent(): SectionAccent = when (this) {
    Section.Training -> TrainingAccent
    Section.Food -> FoodAccent
    Section.Notes -> NotesAccent
}

private fun SectionAccent.tones(darkTheme: Boolean): AccentTones = if (darkTheme) dark else light

private fun ColorScheme.withAccent(tones: AccentTones): ColorScheme = copy(
    primary = tones.primary,
    onPrimary = tones.onPrimary,
    primaryContainer = tones.primaryContainer,
    onPrimaryContainer = tones.onPrimaryContainer,
    surfaceTint = tones.primary,
    inversePrimary = tones.primaryContainer,
)

private fun Color.luminanceIsDark(): Boolean = (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f
