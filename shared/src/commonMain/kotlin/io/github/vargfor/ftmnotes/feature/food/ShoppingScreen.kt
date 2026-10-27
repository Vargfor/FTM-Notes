package io.github.vargfor.ftmnotes.feature.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.food.engine.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ShoppingScreen(id: String, onBack: () -> Unit, onOpenRecipe: (String) -> Unit,
    viewModel: ShoppingViewModel = koinViewModel { parametersOf(id) }) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val settings: SettingsRepository = koinInject()
    val snapshot by settings.snapshot.collectAsStateWithLifecycle()
    val bridge = koinInject<AppPlatform>().bridge
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var orderDialog by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<ShoppingItem?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val updated = stringResource(Res.string.notes_changed)
    val undo = stringResource(Res.string.action_undo)
    LaunchedEffect(viewModel) { viewModel.updates.collect {
        if (snackbar.showSnackbar(updated, undo) == SnackbarResult.ActionPerformed) viewModel.undo()
    } }
    Scaffold(topBar = { BackTopBar(list?.title.orEmpty(), onBack) {
        ActionIcon(Res.drawable.ic_undo, Res.string.action_undo, viewModel.canUndo) { viewModel.undo() }
        ActionIcon(Res.drawable.ic_redo, Res.string.action_redo, viewModel.canRedo) { viewModel.redo() }
        Box {
            ActionIcon(Res.drawable.ic_more_vert, Res.string.action_more) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(Res.string.shopping_clear_checked)) }, { menu = false; viewModel.mutate { it.copy(items = it.items.filterNot { item -> item.checked }) } })
                DropdownMenuItem({ Text(stringResource(Res.string.shopping_aisle_order)) }, { menu = false; orderDialog = true })
                DropdownMenuItem({ Text(stringResource(Res.string.shopping_share)) }, { menu = false; scope.launch { try {
                    val text = list?.let { doc -> doc.title + "\n" + doc.items.filterNot { it.deleted }.joinToString("\n") { item ->
                        (if (item.checked) "☑ " else "☐ ") + FoodEngine.display(Ingredient(raw = item.text, name = item.text, quantity = item.quantity, unit = item.unit), policy = snapshot.foodPolicy()).text()
                    } }.orEmpty()
                    bridge.shareText(text)
                } catch (e: Exception) { problem = e.message } } })
            }
        }
    } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val doc = list
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text(stringResource(Res.string.shopping_quick_add)) })
                ActionIcon(Res.drawable.ic_add, Res.string.action_add, input.isNotBlank()) {
                    val text = input; input = ""; viewModel.add(text) { if (input.isBlank()) input = text }
                }
            }
            if (doc == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                val active = doc.items.filterNot { it.deleted }
                val order = (doc.categoryOrder + Categories.defaults + active.map { it.category }).distinct()
                LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active.isEmpty()) item { Text(stringResource(Res.string.shopping_empty), Modifier.padding(20.dp)) }
                    order.forEach { category ->
                        val items = active.filter { !it.checked && it.category == category }
                        if (items.isNotEmpty()) {
                            item(key = "category-$category") { Text(categoryLabel(category), Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                            items(items, key = { it.id!! }) { item ->
                                ShoppingRow(item, snapshot.foodPolicy(), { viewModel.mutate { doc -> doc.copy(items = doc.items.map { if (it.id == item.id) it.copy(checked = !it.checked) else it }) } },
                                    { edit = item }, { viewModel.mutate { it.copy(items = it.items.filterNot { current -> current.id == item.id }) } }, onOpenRecipe)
                            }
                        }
                    }
                    val checked = active.filter { it.checked }
                    if (checked.isNotEmpty()) {
                        item(key = "cart") { Text(stringResource(Res.string.shopping_in_cart), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium) }
                        items(checked, key = { it.id!! }) { item ->
                            ShoppingRow(item, snapshot.foodPolicy(), { viewModel.mutate { doc -> doc.copy(items = doc.items.map { if (it.id == item.id) it.copy(checked = false) else it }) } },
                                { edit = item }, { viewModel.mutate { it.copy(items = it.items.filterNot { current -> current.id == item.id }) } }, onOpenRecipe)
                        }
                    }
                }
            }
        }
    }
    edit?.let { item -> ShoppingItemDialog(item, { edit = null }) { changed -> viewModel.mutate { it.copy(items = it.items.map { old -> if (old.id == changed.id) changed else old }) } } }
    if (orderDialog) {
        val categories = (list?.categoryOrder.orEmpty() + Categories.defaults + list?.items.orEmpty().map { it.category }).distinct()
        AlertDialog(onDismissRequest = { orderDialog = false }, title = { Text(stringResource(Res.string.shopping_aisle_order)) },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                categories.forEachIndexed { index, category -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(categoryLabel(category), Modifier.weight(1f))
                    MoveControls(index, categories.size) { delta -> viewModel.mutate { it.copy(categoryOrder = moveItem(categories, index, delta)) } }
                } }
            } }, confirmButton = { TextButton({ orderDialog = false }) { Text(stringResource(Res.string.action_done)) } })
    }
    (problem ?: viewModel.error)?.let { message -> AlertDialog(onDismissRequest = { problem = null; viewModel.clearError() },
        text = { Text(stringResource(Res.string.status_error, message)) },
        confirmButton = { TextButton({ problem = null; viewModel.clearError() }) { Text(stringResource(Res.string.action_close)) } }) }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, policy: FoodPolicy, toggle: () -> Unit, edit: () -> Unit, remove: () -> Unit, openRecipe: (String) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().cardOutline()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(item.checked, { toggle() })
            Column(Modifier.weight(1f).clickable(onClick = edit).padding(vertical = 8.dp)) {
                Text(FoodEngine.display(Ingredient(raw = item.text, name = item.text, quantity = item.quantity, unit = item.unit), policy = policy).text(), style = MaterialTheme.typography.bodyLarge)
                if (item.sources.isNotEmpty()) TextButton({ openRecipe(item.sources.first().recipeId) }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(Res.string.shopping_sources, item.sources.map { it.recipeId }.distinct().size), style = MaterialTheme.typography.labelSmall)
                }
            }
            ActionIcon(Res.drawable.ic_close, Res.string.action_delete, onClick = remove)
        }
    }
}

@Composable
private fun ShoppingItemDialog(item: ShoppingItem, dismiss: () -> Unit, save: (ShoppingItem) -> Unit) {
    var edited by remember(item.id) { mutableStateOf(item) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.shopping_edit_item)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(edited.text, { edited = edited.copy(text = it) }, label = { Text(stringResource(Res.string.food_name)) })
            QuantityEntry(edited.quantity) { edited = edited.copy(quantity = it) }
            OutlinedTextField(edited.unit.orEmpty(), { edited = edited.copy(unit = Units.find(it)?.id ?: it.ifBlank { null }) }, label = { Text(stringResource(Res.string.food_unit)) })
            OutlinedTextField(edited.category, { edited = edited.copy(category = it.ifBlank { "other" }) }, label = { Text(stringResource(Res.string.food_category)) })
        } },
        confirmButton = { TextButton({ save(edited); dismiss() }, enabled = edited.text.isNotBlank()) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.action_cancel)) } })
}

@Composable internal fun categoryLabel(category: String): String = when (category) {
    "produce" -> stringResource(Res.string.category_produce); "bakery" -> stringResource(Res.string.category_bakery)
    "dairy" -> stringResource(Res.string.category_dairy); "meat" -> stringResource(Res.string.category_meat)
    "seafood" -> stringResource(Res.string.category_seafood); "pantry" -> stringResource(Res.string.category_pantry)
    "frozen" -> stringResource(Res.string.category_frozen); "other", "" -> stringResource(Res.string.category_other)
    else -> category
}
