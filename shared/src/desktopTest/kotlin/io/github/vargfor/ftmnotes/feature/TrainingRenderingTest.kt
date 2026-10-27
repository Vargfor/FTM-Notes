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
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.designsystem.*
import io.github.vargfor.ftmnotes.feature.training.*
import io.github.vargfor.ftmnotes.feature.importer.*
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalTestApi::class)
class TrainingRenderingTest {
    private enum class Screen { Home, Catalog, Routine, Editor, Workout, Summary, Plan, Import }
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
        val env = ScreenTestEnvironment()
        try {
            val live = runBlocking { seed(env) }
            env.startInjection()
            val owner = Owner(env.models)
            val width = if (tablet) 1280 else 412
            val height = if (tablet) 800 else 915
            var screen by mutableStateOf(Screen.Home)
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { Content(screen, env, owner, live, dark, font) }
                for (next in listOf(Screen.Home, Screen.Catalog, Screen.Routine, Screen.Editor, Screen.Workout, Screen.Summary, Screen.Plan)) {
                    runOnIdle { screen = next }
                    val expected = when(next) {
                        Screen.Catalog -> "Search exercises"
                        Screen.Plan -> "Six week strength"
                        Screen.Summary -> "Monday strength"
                        else -> "Upper A"
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty() }
                    mainClock.advanceTimeBy(500)
                    val bitmap = onRoot().captureToImage()
                    assertEquals(width, bitmap.width); assertEquals(height, bitmap.height)
                    val folder = File("build/reports/screenshots").apply { mkdirs() }
                    val name = "training-" + next.name.lowercase() + "-" + (if (tablet) "tablet" else "phone") + "-" + (if (dark) "dark" else "light") + "-font" + font.toInt() + ".png"
                    assertTrue(ImageIO.write(bitmap.toAwtImage(), "png", File(folder, name)))
                }
            }
        } finally { runBlocking { env.close() } }
    }
    @Test fun keypadCommitsBeforeCompletingASetAndThePlanPromptIsSelfContained() {
        val env = ScreenTestEnvironment()
        try {
            val live = runBlocking { seed(env) }; env.startInjection()
            val owner = Owner(env.models)
            var screen by mutableStateOf(Screen.Workout)
            runDesktopComposeUiTest(width = 412, height = 915) {
                setContent { Content(screen, env, owner, live, false, 1f) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("60").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("60")[0].performClick()
                onNodeWithText("7").performClick(); onNodeWithText("5").performClick()
                onNodeWithText("Done").performClick()
                onAllNodes(isToggleable())[0].performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { runBlocking {
                    (env.repository.get(live)!!.document as WorkoutSessionDocument).exercises.first().sets.first().let { it.weight == 75.0 && it.completed }
                } }
                runOnIdle { screen = Screen.Import }
                onNodeWithText("Use an AI assistant (optional)").performScrollTo().performClick()
                onNodeWithText("Copy prompt").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { env.bridge.copiedText != null }
                val prompt = env.bridge.copiedText!!
                assertTrue(prompt.contains("training_plan")); assertTrue(prompt.contains("workout_template")); assertTrue(prompt.contains("exercise"))
                assertTrue(prompt.contains("documents"))
            }
        } finally { runBlocking { env.close() } }
    }

    @Test fun restEntryRetainsTypingAcrossDatabaseEchoes() {
        val env = ScreenTestEnvironment()
        try {
            val live = runBlocking { seed(env) }; env.startInjection()
            val owner = Owner(env.models)
            runDesktopComposeUiTest(width = 412, height = 915) {
                setContent { Content(Screen.Workout, env, owner, live, false, 1f) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("More options").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("More options").performClick()
                val rest = onNode(hasSetTextAction() and hasText("Rest (seconds)"))
                rest.performTextClearance(); rest.performTextInput("2")
                waitUntil(timeoutMillis = 10_000) { runBlocking { (env.repository.get(live)!!.document as WorkoutSessionDocument).exercises.first().restSeconds == 2 } }
                rest.performTextInput("0")
                waitUntil(timeoutMillis = 10_000) { runBlocking { (env.repository.get(live)!!.document as WorkoutSessionDocument).exercises.first().restSeconds == 20 } }
                onNode(hasSetTextAction() and hasText("20")).assertExists()
            }
        } finally { runBlocking { env.close() } }
    }

    private suspend fun seed(env: ScreenTestEnvironment): String {
        env.ready()
        val exercise = env.repository.save(ExerciseDocument(id = "test-bench", title = "Barbell Bench Press", equipment = "barbell", muscleGroups = listOf("chest", "triceps")))
        env.repository.save(WorkoutTemplateDocument(id = "test-routine", title = "Upper A", notes = "Controlled reps. Leave two reps in reserve.",
            blocks = listOf(WorkoutBlock(exercise = ExerciseRef(exercise, "Barbell Bench Press"), restSeconds = 90,
                sets = List(3) { SetTarget(reps = 8, load = LoadTarget(60.0)) }))))
        env.repository.save(TrainingPlanDocument(id = "test-plan", title = "Six week strength", weeks = 6,
            days = listOf(PlanDay("a", "Upper A", TemplateRef("test-routine", "Upper A"))),
            progression = ProgressionRule(kind = ProgressionKind.Double), notes = "Train three times a week."))
        val history = env.training.start("test-routine")
        env.training.mutate(history) { doc -> doc.copy(title = "Monday strength", startedAt = Clock.System.now() - 2.hours,
            exercises = doc.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(completed = true, weight = 55.0) }) }) }
        env.training.finish(history)
        env.repository.save((env.repository.get(history)!!.document as WorkoutSessionDocument).copy(id = "test-history"))
        return env.training.start("test-routine").also { id ->
            env.training.mutate(id) { doc -> doc.copy(exercises = doc.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(weight = 60.0) }) }) }
        }
    }
    @Composable private fun Content(screen: Screen, env: ScreenTestEnvironment, owner: Owner, live: String, dark: Boolean, font: Float) {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
            FtmTheme(darkTheme = dark, section = Section.Training) {
                Surface(Modifier.fillMaxSize()) {
                    key(screen) {
                        when(screen) {
                            Screen.Home -> TrainingHomeScreen({}, {})
                            Screen.Catalog -> TrainingHomeScreen({}, {}, initialArea = DocumentType.Exercise)
                            Screen.Routine -> TrainingDetailScreen("test-routine", {}, { _, _ -> }, {}, {}, {})
                            Screen.Editor -> TrainingEditorScreen("test-routine", "workout_template", {})
                            Screen.Workout -> WorkoutScreen(live, {}, {})
                            Screen.Summary -> TrainingDetailScreen("test-history", {}, { _, _ -> }, {}, {}, {})
                            Screen.Plan -> TrainingDetailScreen("test-plan", {}, { _, _ -> }, {}, {}, {})
                            Screen.Import -> ImportHomeScreen({}, {}, initialType = DocumentType.TrainingPlan)
                        }
                    }
                }
            }
        }
    }
}
