package app.ftmnotes.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import app.ftmnotes.designsystem.Section
import app.ftmnotes.resources.Res
import app.ftmnotes.resources.ic_fitness_center
import app.ftmnotes.resources.ic_fitness_center_filled
import app.ftmnotes.resources.ic_skillet
import app.ftmnotes.resources.ic_skillet_filled
import app.ftmnotes.resources.ic_sticky_note_2
import app.ftmnotes.resources.ic_sticky_note_2_filled
import app.ftmnotes.resources.tab_food
import app.ftmnotes.resources.tab_notes
import app.ftmnotes.resources.tab_training
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/** Top-level tabs. Each owns an independent back stack that survives switching tabs. */
enum class Tab(
    val section: Section,
    val root: Route,
    val label: StringResource,
    val icon: DrawableResource,
    val selectedIcon: DrawableResource,
) {
    Training(Section.Training, Route.TrainingHome, Res.string.tab_training, Res.drawable.ic_fitness_center, Res.drawable.ic_fitness_center_filled),
    Food(Section.Food, Route.FoodHome, Res.string.tab_food, Res.drawable.ic_skillet, Res.drawable.ic_skillet_filled),
    Notes(Section.Notes, Route.NotesHome, Res.string.tab_notes, Res.drawable.ic_sticky_note_2, Res.drawable.ic_sticky_note_2_filled),
}

/**
 * Owns one back stack per [Tab]. System back pops the current tab's stack; at a tab's root it
 * returns to the start tab (the platform convention for bottom navigation).
 */
@Stable
class AppNavigator internal constructor(
    private val stacks: Map<Tab, NavBackStack<NavKey>>,
    val startTab: Tab,
    currentTabName: MutableState<String>,
) {
    private var currentTabName by currentTabName

    val currentTab: Tab get() = Tab.valueOf(currentTabName)

    fun stack(tab: Tab): NavBackStack<NavKey> = stacks.getValue(tab)

    /** Tabs whose entries are on screen, bottom first: the start tab always sits underneath. */
    val visibleTabs: List<Tab>
        get() = if (currentTab == startTab) listOf(startTab) else listOf(startTab, currentTab)

    /** Selecting the current tab again pops it back to its root, like most platform apps. */
    fun selectTab(tab: Tab) {
        if (tab == currentTab) {
            val stack = stack(tab)
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else {
            currentTabName = tab.name
        }
    }

    fun navigate(route: Route) {
        stack(currentTab).add(route)
    }

    /**
     * Opens a detail destination. If a detail of the same kind is already on top (e.g. another
     * note in the detail pane of a two-pane layout) it is replaced rather than stacked, so back
     * returns to the list instead of walking through every note you looked at.
     */
    fun showDetail(route: Route) {
        val stack = stack(currentTab)
        if (stack.lastOrNull()?.let { it::class == route::class } == true) {
            stack[stack.lastIndex] = route
        } else {
            stack.add(route)
        }
    }

    fun goBack() {
        val stack = stack(currentTab)
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            currentTab != startTab -> currentTabName = startTab.name
        }
    }
}

@Composable
fun rememberAppNavigator(startTab: Tab = Tab.Notes): AppNavigator {
    val training = rememberNavBackStack(NavSavedStateConfiguration, Tab.Training.root)
    val food = rememberNavBackStack(NavSavedStateConfiguration, Tab.Food.root)
    val notes = rememberNavBackStack(NavSavedStateConfiguration, Tab.Notes.root)
    val currentTabName = rememberSaveable { mutableStateOf(startTab.name) }
    return remember(training, food, notes, startTab) {
        AppNavigator(
            stacks = mapOf(Tab.Training to training, Tab.Food to food, Tab.Notes to notes),
            startTab = startTab,
            currentTabName = currentTabName,
        )
    }
}
