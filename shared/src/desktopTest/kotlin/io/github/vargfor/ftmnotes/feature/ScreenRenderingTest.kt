package io.github.vargfor.ftmnotes.feature

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.core.settings.SectionId
import io.github.vargfor.ftmnotes.core.settings.TabLayout
import io.github.vargfor.ftmnotes.designsystem.FtmTheme
import io.github.vargfor.ftmnotes.designsystem.Section
import io.github.vargfor.ftmnotes.feature.food.*
import io.github.vargfor.ftmnotes.feature.notes.*
import io.github.vargfor.ftmnotes.navigation.AppShell
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import io.github.vargfor.ftmnotes.resources.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Captures real screens, with semantic checks; PNGs are review artifacts, not OS-dependent goldens. */
@OptIn(ExperimentalTestApi::class)
class ScreenRenderingTest {
    private enum class Screen { Notes, Editor, Recipe, Cook, Shopping, FoodHome, Import, Shell }
    private class Owner(override val viewModelStore: ViewModelStore) : ViewModelStoreOwner, LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @Test fun phoneLight() = captures(false, 1f, false)
    @Test fun phoneDark() = captures(true, 1f, false)
    @Test fun phoneLightLargeText() = captures(false, 2f, false)
    @Test fun phoneDarkLargeText() = captures(true, 2f, false)
    @Test fun tabletLight() = captures(false, 1f, true)
    @Test fun tabletDark() = captures(true, 1f, true)
    @Test fun tabletLightLargeText() = captures(false, 2f, true)
    @Test fun tabletDarkLargeText() = captures(true, 2f, true)

    private fun captures(dark: Boolean, font: Float, tablet: Boolean) {
        val width = if (tablet) 1280 else 412
        val height = if (tablet) 800 else 915
        val env = ScreenTestEnvironment()
        try {
            runBlocking { env.seed() }
            env.startInjection()
            val owner = Owner(env.models)
            var screen by mutableStateOf(Screen.Notes)
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { Content(screen, env, owner, dark, font) }
                for (next in listOf(Screen.Notes, Screen.Editor, Screen.Recipe, Screen.Cook, Screen.Shopping)) {
                    runOnIdle { screen = next }
                    val text = when (next) {
                        Screen.Notes, Screen.Editor -> "Weekend notes"
                        Screen.Recipe, Screen.Cook -> "Lemon chicken"
                        else -> "This week's groceries"
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
                    mainClock.advanceTimeBy(1_000)
                    if (next == Screen.Cook) onNodeWithText("Step 1 of 2").assertIsDisplayed()
                    if (next == Screen.Shopping) onNodeWithText("Milk", substring = true).assertExists()
                    waitForIdle()
                    val bitmap = onRoot().captureToImage()
                    assertEquals(width, bitmap.width)
                    assertEquals(height, bitmap.height)
                    val pixels = bitmap.toAwtImage()
                    val colors = (0 until width step 8).flatMap { x -> (0 until height step 8).map { y -> pixels.getRGB(x, y) } }.toSet()
                    assertTrue(colors.size > 10, "A screen should contain rendered content")
                    val folder = File("build/reports/screenshots").apply { mkdirs() }
                    val filename = next.name.lowercase() + "-" + (if (tablet) "tablet" else "phone") + "-" + (if (dark) "dark" else "light") + "-font" + font.toInt() + ".png"
                    assertTrue(ImageIO.write(pixels, "png", File(folder, filename)))
                }
            }
        } finally { runBlocking { env.close() } }
    }

    @Test fun scalingPresetsDoNotChangeStoredRecipeAndMultipleListSettingControlsCreation() {
        val env = ScreenTestEnvironment()
        try {
            runBlocking { env.seed() }
            env.startInjection()
            val owner = Owner(env.models)
            var screen by mutableStateOf(Screen.Recipe)
            val before = runBlocking { FtmFormat.encodeDocument(env.repository.get("recipe")!!.document) }
            runDesktopComposeUiTest(width = 412, height = 915) {
                setContent { Content(screen, env, owner, false, 1f) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Lemon chicken").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasScrollToNodeAction()).performScrollToNode(hasText("2×"))
                onNodeWithText("2×").performScrollTo().performClick()
                onNodeWithText("8 portions").assertExists()
                onNodeWithText("½×").performClick()
                onNodeWithText("2 portions").assertExists()
                onNodeWithText("3×").performClick()
                onNodeWithText("12 portions").assertExists()
                assertEquals(before, FtmFormat.encodeDocument(env.repository.get("recipe")!!.document))
                runOnIdle { screen = Screen.FoodHome }
                onNodeWithText("Shopping lists").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("This week's groceries").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("New shopping list").assertDoesNotExist()
                env.settings.set(Flags.foodMultipleLists, true)
                env.settings.observe(Flags.foodMultipleLists).first { it }
                mainClock.advanceTimeBy(1_000)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("New shopping list").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("New shopping list").performClick()
                onNode(hasSetTextAction()).performTextReplacement("Groceries 2")
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Groceries 2").fetchSemanticsNodes().isNotEmpty() }
                env.settings.set(Flags.foodMultipleLists, false)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("New shopping list").fetchSemanticsNodes().isEmpty() }
                onNodeWithText("This week's groceries").assertExists()
                onNodeWithText("Groceries 2").assertExists()
                assertEquals(2, env.repository.observeList(DocumentType.ShoppingList, false).first().size)
            }
        } finally { runBlocking { env.close() } }
    }

    @Test fun recipePromptCanBeCopiedFromTheImportScreen() {
        val env = ScreenTestEnvironment()
        try {
            runBlocking { env.ready() }
            env.startInjection()
            val owner = Owner(env.models)
            val label = runBlocking { getString(Res.string.import_copy_prompt) }
            runDesktopComposeUiTest(width = 412, height = 915) {
                setContent { Content(Screen.Import, env, owner, false, 1f) }
                onNodeWithText("Use an AI assistant (optional)").performScrollTo().performClick()
                onNodeWithText(label).performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { env.bridge.copiedText != null }
                assertTrue(env.bridge.copiedText!!.contains("Tomato soup"))
                assertTrue(env.bridge.copiedText!!.contains("servings"))
            }
        } finally { runBlocking { env.close() } }
    }

    @Test fun tabletTabsKeepTheirOwnDetailPaneAndRestoreThePreviousNote() {
        val env = ScreenTestEnvironment()
        try {
            runBlocking { env.seed() }
            env.startInjection()
            val owner = Owner(env.models)
            val placeholder = runBlocking { getString(Res.string.food_select_hint) }
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { Content(Screen.Shell, env, owner, false, 1f) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Weekend notes").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Weekend notes").performClick()
                onNode(hasSetTextAction() and hasText("A little breathing room", substring = true)).assertExists()
                onNodeWithText("Food").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Lemon chicken").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(placeholder).assertExists()
                onAllNodesWithText("Weekend notes").assertCountEquals(0)
                onNodeWithText("Lemon chicken").performClick()
                onNodeWithText("Cook mode").assertExists()
                onNodeWithText("Notes").performClick()
                onNode(hasSetTextAction() and hasText("A little breathing room", substring = true)).assertExists()
            }
        } finally { runBlocking { env.close() } }
    }

    @Composable private fun Content(screen: Screen, env: ScreenTestEnvironment, owner: Owner, dark: Boolean, font: Float) {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalLifecycleOwner provides owner,
            LocalViewModelStoreOwner provides owner) {
            FtmTheme(darkTheme = dark, section = if (screen in listOf(Screen.Notes, Screen.Editor)) Section.Notes else Section.Food) {
                Surface(Modifier.fillMaxSize()) {
                    key(screen) {
                        when (screen) {
                            Screen.Notes -> NotesListScreen({}, {}, {}, {})
                            Screen.Editor -> NoteEditorScreen("note", {})
                            Screen.Recipe -> RecipeScreen("recipe", {}, {}, {}, {})
                            Screen.Cook -> RecipeScreen("recipe", {}, {}, {}, {}, cooking = true)
                            Screen.Shopping -> ShoppingScreen("shopping", {}, {})
                            Screen.FoodHome -> FoodHomeScreen({}, {})
                            Screen.Import -> FoodImportScreen({}, {})
                            Screen.Shell -> AppShell(TabLayout(SectionId.entries, SectionId.Notes))
                        }
                    }
                }
            }
        }
    }
}
