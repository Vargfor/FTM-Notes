package app.ftmnotes.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.ftmnotes.core.model.Ids
import app.ftmnotes.designsystem.SectionTheme
import app.ftmnotes.feature.food.FoodHomeScreen
import app.ftmnotes.feature.notes.NoteEditorScreen
import app.ftmnotes.feature.notes.NoteSelectionPlaceholder
import app.ftmnotes.feature.notes.NotesListScreen
import app.ftmnotes.feature.search.SearchScreen
import app.ftmnotes.feature.settings.SettingsScreen
import app.ftmnotes.feature.training.TrainingHomeScreen
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Root of the UI: adaptive navigation (bottom bar on phones, rail on wide screens) around a
 * [NavDisplay] that shows the current tab's back stack, with list-detail panes where useful.
 */
@Composable
fun AppShell() {
    val navigator = rememberAppNavigator()
    val listDetail = rememberListDetailSceneStrategy<NavKey>()

    // Decorate each tab's stack separately so saved UI state and ViewModels of background tabs
    // stay alive while another tab is shown.
    val entriesByTab: Map<Tab, List<NavEntry<NavKey>>> = Tab.entries.associateWith { tab ->
        key(tab) {
            rememberDecoratedNavEntries(
                backStack = navigator.stack(tab),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = appEntryProvider(tab, navigator),
            )
        }
    }

    val currentTab = navigator.currentTab
    SectionTheme(currentTab.section) {
        // Selected tab picks up the section accent instead of the neutral default.
        val itemColors = ShortNavigationBarItemDefaults.colors().copy(
            selectedIndicatorColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
        )
        NavigationSuiteScaffold(
            navigationItems = {
                Tab.entries.forEach { tab ->
                    val selected = tab == currentTab
                    NavigationSuiteItem(
                        selected = selected,
                        onClick = { navigator.selectTab(tab) },
                        colors = itemColors,
                        icon = {
                            Icon(
                                painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            },
        ) {
            NavDisplay(
                entries = navigator.visibleTabs.flatMap { entriesByTab.getValue(it) },
                sceneStrategies = listOf(listDetail),
                onBack = navigator::goBack,
            )
        }
    }
}

private fun appEntryProvider(tab: Tab, navigator: AppNavigator): (NavKey) -> NavEntry<NavKey> {
    // Prefix with the tab so the same route (e.g. Settings) can live in two stacks at once.
    val contentKey: (NavKey) -> Any = { key -> "${tab.name}/$key" }
    val openSearch = { navigator.navigate(Route.Search) }
    val openSettings = { navigator.navigate(Route.Settings) }

    return entryProvider {
        entry<Route.TrainingHome>(clazzContentKey = contentKey) {
            SectionTheme(tab.section) {
                TrainingHomeScreen(onOpenSearch = openSearch, onOpenSettings = openSettings)
            }
        }
        entry<Route.FoodHome>(clazzContentKey = contentKey) {
            SectionTheme(tab.section) {
                FoodHomeScreen(onOpenSearch = openSearch, onOpenSettings = openSettings)
            }
        }
        entry<Route.NotesHome>(
            clazzContentKey = contentKey,
            metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { NoteSelectionPlaceholder() }),
        ) {
            SectionTheme(tab.section) {
                NotesListScreen(
                    onOpenNote = { id -> navigator.showDetail(Route.NoteEditor(id)) },
                    onNewNote = { navigator.showDetail(Route.NoteEditor(Ids.newId())) },
                    onOpenSearch = openSearch,
                    onOpenSettings = openSettings,
                )
            }
        }
        entry<Route.NoteEditor>(
            clazzContentKey = contentKey,
            metadata = ListDetailSceneStrategy.detailPane(),
        ) { route ->
            SectionTheme(tab.section) {
                NoteEditorScreen(noteId = route.noteId, onBack = navigator::goBack)
            }
        }
        entry<Route.Settings>(clazzContentKey = contentKey) {
            SettingsScreen(onBack = navigator::goBack)
        }
        entry<Route.Search>(clazzContentKey = contentKey) {
            SearchScreen(onBack = navigator::goBack)
        }
    }
}
