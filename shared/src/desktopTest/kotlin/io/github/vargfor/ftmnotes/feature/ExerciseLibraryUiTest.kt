package io.github.vargfor.ftmnotes.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.data.BundledExerciseCatalog
import io.github.vargfor.ftmnotes.designsystem.FtmTheme
import io.github.vargfor.ftmnotes.feature.training.*
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ExerciseLibraryUiTest {
    @Test fun fullLibraryPickerFiltersAndSelectsWithoutComposingAllExercises() = picker(1f)
    @Test fun largeTextPickerRemainsScrollableAndUsable() = picker(2f)

    private fun picker(font: Float) {
        val catalog = runBlocking { BundledExerciseCatalog.load() }
        var open by mutableStateOf(true)
        var chosen: ExerciseDocument? = null
        runDesktopComposeUiTest(width = 412, height = 915) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, font)) {
                    FtmTheme(darkTheme = font > 1f) {
                        Surface(Modifier.fillMaxSize()) {
                            if (open) ExercisePicker(catalog, { open = false }) { chosen = it }
                        }
                    }
                }
            }
            // Hundreds of off-screen exercises must not be composed at once.
            assertTrue(onAllNodes(hasClickAction()).fetchSemanticsNodes().size < 80)
            onNodeWithText("Activity: All").performScrollTo().performClick()
            onNodeWithText("Stretching").performClick()
            onNodeWithText("Search exercises").performScrollTo().performTextInput("90/90")
            onNodeWithText("90/90 Hamstring").performScrollTo().assertIsDisplayed()
            val folder = File("build/reports/screenshots").apply { mkdirs() }
            val dialog = onNode(isRoot() and hasAnyDescendant(hasText("Search exercises")))
            assertTrue(ImageIO.write(dialog.captureToImage().toAwtImage(), "png", File(folder, "exercise-picker-font${font.toInt()}.png")))
            onNodeWithText("90/90 Hamstring").performClick()
            assertEquals("stretching", chosen?.category)
            assertFalse(open)
        }
    }

    @Test fun plateInventoryUsesLabelledFieldsRejectsDuplicatesAndPreservesOtherWeights() {
        var saved by mutableStateOf("20:4,10:2")
        runDesktopComposeUiTest(width = 412, height = 915) {
            setContent { FtmTheme { Surface(Modifier.fillMaxSize()) {
                Column { PlateInventoryEditor(saved, MassUnit.Kg) { saved = it } }
            } } }
            onAllNodesWithText("Edit")[0].performClick()
            onNodeWithText("Number of plates").performTextClearance()
            onNodeWithText("Number of plates").performTextInput("0")
            onNodeWithText("Done").performClick()
            assertEquals("20.0:0,10.0:2", saved)
            onNodeWithText("Add a plate weight").performClick()
            onNodeWithText("Weight of one plate").performTextInput("10")
            onNodeWithText("Done").assertIsNotEnabled()
            onNodeWithText("This weight is already in your list. Edit its quantity instead.").assertIsDisplayed()
            onNodeWithText("Weight of one plate").performTextClearance()
            onNodeWithText("Weight of one plate").performTextInput("-2")
            onNodeWithText("Done").assertIsNotEnabled()
            onNodeWithText("Weight of one plate").performTextClearance()
            onNodeWithText("Weight of one plate").performTextInput("2.5")
            onNodeWithText("Done").performClick()
            assertEquals("20.0:0,10.0:2,2.5:2", saved)
        }
    }
}
