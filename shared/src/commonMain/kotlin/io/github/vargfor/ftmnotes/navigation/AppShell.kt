package io.github.vargfor.ftmnotes.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.vargfor.ftmnotes.core.model.Ids
import io.github.vargfor.ftmnotes.core.settings.SettingsSnapshot
import io.github.vargfor.ftmnotes.core.settings.TabLayout
import io.github.vargfor.ftmnotes.designsystem.SectionTheme
import io.github.vargfor.ftmnotes.feature.food.FoodHomeScreen
import io.github.vargfor.ftmnotes.feature.food.*
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.feature.notes.NoteEditorScreen
import io.github.vargfor.ftmnotes.feature.notes.NoteSelectionPlaceholder
import io.github.vargfor.ftmnotes.feature.notes.NotesListScreen
import io.github.vargfor.ftmnotes.feature.search.SearchScreen
import io.github.vargfor.ftmnotes.feature.settings.SettingsScreen
import io.github.vargfor.ftmnotes.feature.training.TrainingHomeScreen
import io.github.vargfor.ftmnotes.feature.importer.ImportHomeScreen
import io.github.vargfor.ftmnotes.feature.importer.ImportPreviewScreen
import io.github.vargfor.ftmnotes.feature.backup.BackupScreen
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.AttachmentStore
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.model.NoteKind
import io.github.vargfor.ftmnotes.core.format.MarkdownLines
import androidx.compose.runtime.LaunchedEffect
import org.koin.compose.koinInject
import okio.FileSystem
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Root of the UI: adaptive navigation (bottom bar on phones, rail on wide screens) around a
 * [NavDisplay] that shows the current tab's back stack, with list-detail panes where useful.
 */
@Composable
fun AppShell(tabLayout: TabLayout = SettingsSnapshot.Defaults.tabLayout()) {
    val enabledTabs = remember(tabLayout) { tabLayout.tabs.map(Tab::of) }
    val navigator = rememberAppNavigator(startTab = Tab.of(tabLayout.start), enabledTabs = enabledTabs)
    val bridge = koinInject<AppPlatform>().bridge
    val repository: DocumentRepository = koinInject()
    val attachments: AttachmentStore = koinInject()
    LaunchedEffect(bridge) {
        bridge.incoming.collect { input ->
            val id = if (input.text.isNullOrBlank() && input.files.isEmpty()) Ids.newId()
            else repository.save(NoteDocument(content = MarkdownLines.fromPlainText(input.text.orEmpty()),
                attachments = input.files.map { file -> attachments.put(FileSystem.SYSTEM.source(file.path), file.mime, file.name) }))
            if (Tab.Notes in navigator.enabledTabs) navigator.selectTab(Tab.Notes)
            navigator.showDetail(Route.NoteEditor(id, input.checklist))
        }
    }
    val listDetail = rememberListDetailSceneStrategy<NavKey>()
    // A single section needs no navigation bar.
    val navigationSuiteType = if (enabledTabs.size > 1) {
        NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
    } else {
        NavigationSuiteType.None
    }

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
            navigationSuiteType = navigationSuiteType,
            navigationItems = {
                navigator.enabledTabs.forEach { tab ->
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
        entry<Route.FoodHome>(clazzContentKey = contentKey,
            metadata = ListDetailSceneStrategy.listPane(sceneKey = tab, detailPlaceholder = { FoodSelectionPlaceholder() })) {
            SectionTheme(tab.section) {
                FoodHomeScreen(onOpenSearch = openSearch, onOpenSettings = openSettings,
                    onOpenRecipe = { navigator.showDetail(Route.Recipe(it)) }, onNewRecipe = { navigator.showDetail(Route.RecipeEditor(Ids.newId())) },
                    onOpenShopping = { navigator.showDetail(Route.ShoppingList(it)) }, onOpenImport = { navigator.navigate(Route.FoodImport) },
                    onOpenArchive = { navigator.navigate(Route.Archive(it)) }, onOpenTrash = { navigator.navigate(Route.Trash(it)) })
            }
        }
        entry<Route.NotesHome>(
            clazzContentKey = contentKey,
            metadata = ListDetailSceneStrategy.listPane(sceneKey = tab, detailPlaceholder = { NoteSelectionPlaceholder() }),
        ) {
            SectionTheme(tab.section) {
                NotesListScreen(
                    onOpenNote = { id -> navigator.showDetail(Route.NoteEditor(id)) },
                    onNewNote = { navigator.showDetail(Route.NoteEditor(Ids.newId())) },
                    onOpenSearch = openSearch,
                    onOpenSettings = openSettings,
                    onOpenArchive = { navigator.navigate(Route.Archive()) },
                    onOpenTrash = { navigator.navigate(Route.Trash()) },
                    onOpenImport = { navigator.navigate(Route.ImportHome) },
                    onNewChecklist = { navigator.showDetail(Route.NoteEditor(Ids.newId(), checklist = true)) },
                )
            }
        }
        entry<Route.NoteEditor>(
            clazzContentKey = contentKey,
            metadata = ListDetailSceneStrategy.detailPane(sceneKey = tab),
        ) { route ->
            SectionTheme(tab.section) {
                NoteEditorScreen(noteId = route.noteId, onBack = navigator::goBack, initialChecklist = route.checklist,
                    onCloned = { navigator.showDetail(Route.NoteEditor(it)) })
            }
        }
        entry<Route.Settings>(clazzContentKey = contentKey) {
            SettingsScreen(onBack = navigator::goBack,
                onOpenImport = { navigator.navigate(Route.ImportHome) }, onOpenBackups = { navigator.navigate(Route.Backups) },
                onOpenFoodSettings = { navigator.navigate(Route.FoodSettings) })
        }
        entry<Route.Archive>(clazzContentKey = contentKey) { route ->
            NotesListScreen(
                onOpenNote = { id -> when (DocumentType.fromWire(route.type)) { DocumentType.Recipe -> navigator.showDetail(Route.Recipe(id)); DocumentType.ShoppingList -> navigator.showDetail(Route.ShoppingList(id)); else -> navigator.showDetail(Route.NoteEditor(id)) } }, onNewNote = {},
                onOpenSearch = openSearch, onOpenSettings = openSettings,
                mode = io.github.vargfor.ftmnotes.feature.notes.NoteCollection.Archive, onBack = navigator::goBack,
                documentType = DocumentType.fromWire(route.type) ?: DocumentType.Note,
            )
        }
        entry<Route.Trash>(clazzContentKey = contentKey) { route ->
            NotesListScreen(
                onOpenNote = {}, onNewNote = {}, onOpenSearch = openSearch, onOpenSettings = openSettings,
                mode = io.github.vargfor.ftmnotes.feature.notes.NoteCollection.Trash, onBack = navigator::goBack,
                documentType = DocumentType.fromWire(route.type) ?: DocumentType.Note,
            )
        }
        entry<Route.Search>(clazzContentKey = contentKey) {
            SearchScreen(onBack = navigator::goBack, onOpen = { id, type ->
                when (type) {
                    DocumentType.Note -> navigator.showDetail(Route.NoteEditor(id))
                    DocumentType.Recipe -> navigator.showDetail(Route.Recipe(id))
                    DocumentType.ShoppingList -> navigator.showDetail(Route.ShoppingList(id))
                    else -> Unit
                }
            })
        }
        entry<Route.ImportHome>(clazzContentKey = contentKey) {
            ImportHomeScreen(navigator::goBack, onPreview = { navigator.navigate(Route.ImportPreview(it)) }, onFoodImport = { navigator.navigate(Route.FoodImport) })
        }
        entry<Route.ImportPreview>(clazzContentKey = contentKey) { route ->
            ImportPreviewScreen(route.id, navigator::goBack)
        }
        entry<Route.Backups>(clazzContentKey = contentKey) {
            BackupScreen(navigator::goBack)
        }
        entry<Route.Recipe>(clazzContentKey = contentKey, metadata = ListDetailSceneStrategy.detailPane(sceneKey = tab)) { route ->
            RecipeScreen(route.id, navigator::goBack, { navigator.showDetail(Route.RecipeEditor(route.id)) },
                { navigator.navigate(Route.CookMode(route.id)) }, { navigator.navigate(Route.FoodSettings) })
        }
        entry<Route.RecipeEditor>(clazzContentKey = contentKey, metadata = ListDetailSceneStrategy.detailPane(sceneKey = tab)) { route ->
            RecipeEditorScreen(route.id, navigator::goBack)
        }
        entry<Route.CookMode>(clazzContentKey = contentKey) { route ->
            RecipeScreen(route.id, navigator::goBack, {}, {}, { navigator.navigate(Route.FoodSettings) }, cooking = true)
        }
        entry<Route.ShoppingList>(clazzContentKey = contentKey, metadata = ListDetailSceneStrategy.detailPane(sceneKey = tab)) { route ->
            ShoppingScreen(route.id, navigator::goBack, onOpenRecipe = { navigator.showDetail(Route.Recipe(it)) })
        }
        entry<Route.FoodSettings>(clazzContentKey = contentKey) { FoodSettingsScreen(navigator::goBack) }
        entry<Route.FoodImport>(clazzContentKey = contentKey) { FoodImportScreen(navigator::goBack, { navigator.navigate(Route.ImportPreview(it)) }) }
    }
}
