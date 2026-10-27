package io.github.vargfor.ftmnotes.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.vargfor.ftmnotes.core.model.ProgressionKind
import io.github.vargfor.ftmnotes.designsystem.FtmTheme
import io.github.vargfor.ftmnotes.designsystem.components.LocalHelpIcons
import io.github.vargfor.ftmnotes.feature.food.NumberEntry
import io.github.vargfor.ftmnotes.feature.settings.ChoiceRow
import io.github.vargfor.ftmnotes.feature.settings.SwitchRow
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ContextHelpTest {
    @Test fun helpExplainsControlsWithoutChangingTheirValues() = scenario(1f, false)
    @Test fun largeTextHelpScrollsAndCanBeDismissedOrHidden() = scenario(2f, true)

    private fun scenario(font: Float, dark: Boolean) {
        val title = runBlocking { getString(Res.string.training_auto_advance) }
        val switchHelp = runBlocking { getString(Res.string.help_for, title) }
        val rpeTitle = runBlocking { getString(Res.string.training_rpe) }
        val rpeHelp = runBlocking { getString(Res.string.help_for, rpeTitle) }
        val explanation = runBlocking { getString(Res.string.help_auto_advance) }
        val effort = runBlocking { getString(Res.string.help_effort) }
        val close = runBlocking { getString(Res.string.action_close) }
        var checked by mutableStateOf(false)
        var number by mutableStateOf<Double?>(7.5)
        var selected by mutableStateOf(ProgressionKind.Double)
        var showHelp by mutableStateOf(true)
        runDesktopComposeUiTest(width = 412, height = 915) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalHelpIcons provides showHelp) {
                    FtmTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                SwitchRow(title, checked, { checked = it }, help = Res.string.help_auto_advance)
                                NumberEntry(Res.string.training_rpe, number, help = Res.string.help_effort) { number = it }
                                ChoiceRow(stringResource(Res.string.training_progression), ProgressionKind.entries, selected,
                                    { it.name }, help = Res.string.help_progression) { selected = it }
                            }
                        }
                    }
                }
            }
            onNodeWithContentDescription(switchHelp).performClick()
            onNodeWithText(explanation).assertExists()
            onNodeWithText(close).assertIsDisplayed()
            assertFalse(checked)
            assertEquals(7.5, number)
            assertEquals(ProgressionKind.Double, selected)
            onNodeWithText(close).performClick()
            onNodeWithText(explanation).assertDoesNotExist()

            onNodeWithContentDescription(rpeHelp).performScrollTo().performClick()
            onNodeWithText(effort).assertExists()
            if (font > 1f) {
                onNodeWithText(effort).performTouchInput { swipeUp() }
                val scroll = onNodeWithText(effort).fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(scroll.value() > 0f)
            }
            onNodeWithText(close).assertIsDisplayed()
            val file = File("build/reports/screenshots/help-phone-" + (if (dark) "dark" else "light") + "-font" + font.toInt() + ".png")
            file.parentFile.mkdirs()
            val dialog = onNode(isRoot() and hasAnyDescendant(hasText(effort)))
            assertTrue(ImageIO.write(dialog.captureToImage().toAwtImage(), "png", file))
            onNodeWithText(close).performClick()
            assertEquals(7.5, number)

            // The help button must remain a separate target from its surrounding switch row.
            onNodeWithText(title).performScrollTo().performClick()
            assertTrue(checked)
            runOnIdle { showHelp = false }
            onNodeWithContentDescription(switchHelp).assertDoesNotExist()
            onNodeWithContentDescription(rpeHelp).assertDoesNotExist()
            assertEquals(7.5, number)
            assertEquals(ProgressionKind.Double, selected)
        }
    }
}
