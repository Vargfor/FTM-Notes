package io.github.vargfor.ftmnotes.feature.food

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.Transactor
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.feature.notes.NotesListScreen
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@Composable
fun FoodHomeScreen(
    onOpenSearch: () -> Unit, onOpenSettings: () -> Unit,
    onOpenRecipe: (String) -> Unit = {}, onNewRecipe: () -> Unit = {},
    onOpenShopping: (String) -> Unit = {}, onOpenImport: () -> Unit = {},
    onOpenArchive: (String) -> Unit = {}, onOpenTrash: (String) -> Unit = {},
) {
    var shopping by rememberSaveable { mutableStateOf(false) }
    var createList by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val repository: DocumentRepository = koinInject()
    val settings: SettingsRepository = koinInject()
    val transactor: Transactor = koinInject()
    val snapshot by settings.snapshot.collectAsStateWithLifecycle()
    val lists by remember(repository) { repository.observeList(DocumentType.ShoppingList, false) }.collectAsStateWithLifecycle(emptyList())
    val canCreateList = snapshot[Flags.foodMultipleLists] || lists.isEmpty()
    val scope = rememberCoroutineScope()
    val type = if (shopping) DocumentType.ShoppingList else DocumentType.Recipe
    NotesListScreen(onOpenNote = { if (shopping) onOpenShopping(it) else onOpenRecipe(it) },
        onNewNote = { if (shopping) { if (canCreateList) createList = true } else onNewRecipe() },
        onOpenSearch = onOpenSearch, onOpenSettings = onOpenSettings,
        onOpenImport = onOpenImport, onOpenArchive = { onOpenArchive(type.wire) }, onOpenTrash = { onOpenTrash(type.wire) },
        documentType = type, canCreate = !shopping || canCreateList, contentHeader = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(!shopping, { shopping = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(Res.string.food_recipes)) }
                SegmentedButton(shopping, { shopping = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(Res.string.type_shopping_list)) }
            }
            if (shopping && !canCreateList) Text(stringResource(Res.string.shopping_single_list_hint),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        })
    if (createList) TextEntryDialog(Res.string.shopping_new, stringResource(Res.string.shopping_default_title), onDismiss = { createList = false }) { title ->
        scope.launch { try {
            val id = transactor.write {
                val existing = repository.observeList(DocumentType.ShoppingList, false).first()
                if (!settings.snapshot.value[Flags.foodMultipleLists] && existing.isNotEmpty()) existing.first().id
                else repository.save(ShoppingListDocument(title = title))
            }
            onOpenShopping(id)
        } catch (e: Exception) { problem = e.message } }
    }
    problem?.let { AlertDialog(onDismissRequest = { problem = null }, text = { Text(stringResource(Res.string.status_error, it)) },
        confirmButton = { TextButton({ problem = null }) { Text(stringResource(Res.string.action_close)) } }) }
}

@Composable
fun FoodSelectionPlaceholder() {
    EmptyState(painterResource(Res.drawable.ic_skillet), null, stringResource(Res.string.food_select_hint))
}
