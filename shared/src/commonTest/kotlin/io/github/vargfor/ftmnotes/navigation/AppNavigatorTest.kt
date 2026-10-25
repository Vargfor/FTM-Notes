package io.github.vargfor.ftmnotes.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals

class AppNavigatorTest {

    // Shared like rememberAppNavigator's saved state, so a navigator rebuilt after a settings change sees it.
    private val stacks = Tab.entries.associateWith { NavBackStack<NavKey>(it.root) }
    private val currentTabName = mutableStateOf(Tab.Notes.name)

    private fun navigator(startTab: Tab = Tab.Notes, enabledTabs: List<Tab> = Tab.entries) =
        AppNavigator(stacks, startTab, currentTabName, enabledTabs)

    @Test
    fun eachTabKeepsItsOwnBackStackWhenSwitching() {
        val nav = navigator()
        nav.navigate(Route.NoteEditor("a"))
        nav.selectTab(Tab.Food)
        nav.navigate(Route.Settings)
        nav.selectTab(Tab.Notes)

        assertEquals(listOf<NavKey>(Route.NotesHome, Route.NoteEditor("a")), nav.stack(Tab.Notes).toList())
        assertEquals(listOf<NavKey>(Route.FoodHome, Route.Settings), nav.stack(Tab.Food).toList())
    }

    @Test
    fun backAtTheRootOfAnotherTabReturnsToTheStartTab() {
        val nav = navigator()
        nav.selectTab(Tab.Training)
        assertEquals(listOf(Tab.Notes, Tab.Training), nav.visibleTabs)

        nav.goBack()
        assertEquals(Tab.Notes, nav.currentTab)
        assertEquals(listOf(Tab.Notes), nav.visibleTabs)
    }

    @Test
    fun reselectingTheCurrentTabPopsToItsRoot() {
        val nav = navigator()
        nav.navigate(Route.NoteEditor("a"))
        nav.navigate(Route.Settings)
        nav.selectTab(Tab.Notes)
        assertEquals(listOf<NavKey>(Route.NotesHome), nav.stack(Tab.Notes).toList())
    }

    @Test
    fun showDetailReplacesADetailOfTheSameKindInsteadOfStacking() {
        val nav = navigator()
        nav.showDetail(Route.NoteEditor("a"))
        nav.showDetail(Route.NoteEditor("b"))
        assertEquals(listOf<NavKey>(Route.NotesHome, Route.NoteEditor("b")), nav.stack(Tab.Notes).toList())

        nav.goBack()
        assertEquals(listOf<NavKey>(Route.NotesHome), nav.stack(Tab.Notes).toList())
    }

    @Test
    fun hidingTheSelectedTabFallsBackToTheStartTabAndKeepsItsStack() {
        navigator().apply {
            selectTab(Tab.Food)
            navigate(Route.Settings)
        }
        // Settings change: Food is hidden, so a new navigator is built with fewer tabs.
        val nav = navigator(enabledTabs = listOf(Tab.Training, Tab.Notes))
        assertEquals(Tab.Notes, nav.currentTab)
        assertEquals(listOf(Tab.Notes), nav.visibleTabs)

        nav.selectTab(Tab.Food) // not in the bar: ignored
        assertEquals(Tab.Notes, nav.currentTab)

        // Showing Food again restores where it was.
        val shown = navigator()
        assertEquals(Tab.Food, shown.currentTab)
        assertEquals(listOf<NavKey>(Route.FoodHome, Route.Settings), shown.stack(Tab.Food).toList())
    }

    @Test
    fun aDifferentStartTabIsWhereBackEndsUp() {
        currentTabName.value = Tab.Training.name // a fresh launch opens on the start tab
        val nav = navigator(startTab = Tab.Training)
        assertEquals(Tab.Training, nav.currentTab)
        nav.selectTab(Tab.Notes)
        nav.goBack()
        assertEquals(Tab.Training, nav.currentTab)
    }
}
