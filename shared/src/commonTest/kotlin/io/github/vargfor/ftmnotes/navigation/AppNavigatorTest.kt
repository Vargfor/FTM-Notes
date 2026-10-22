package io.github.vargfor.ftmnotes.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals

class AppNavigatorTest {

    private fun navigator(startTab: Tab = Tab.Notes) = AppNavigator(
        stacks = Tab.entries.associateWith { NavBackStack<NavKey>(it.root) },
        startTab = startTab,
        currentTabName = mutableStateOf(startTab.name),
    )

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
}
