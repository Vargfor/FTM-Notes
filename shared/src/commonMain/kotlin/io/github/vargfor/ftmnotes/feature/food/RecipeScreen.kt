package io.github.vargfor.ftmnotes.feature.food

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.food.engine.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecipeScreen(id: String, onBack: () -> Unit, onEdit: () -> Unit, onCook: () -> Unit, onSettings: () -> Unit,
    cooking: Boolean = false, viewModel: RecipeViewModel = koinViewModel { parametersOf(id) }) {
    val settings: SettingsRepository = koinInject()
    val snapshot by settings.snapshot.collectAsStateWithLifecycle()
    val bridge = koinInject<AppPlatform>().bridge
    val repository: DocumentRepository = koinInject()
    val scope = rememberCoroutineScope()
    val recipe = viewModel.recipe
    val state = viewModel.state
    var menu by remember { mutableStateOf(false) }
    var scaleDialog by remember { mutableStateOf(false) }
    var shopDialog by remember { mutableStateOf(false) }
    var servingsDialog by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val numberError = stringResource(Res.string.food_positive_number)
    val mode = UnitMode.entries.firstOrNull { it.wire == state.units } ?: snapshot[Flags.foodUnits]
    val policy = snapshot.foodPolicy().copy(target = mode)
    val servings = state.servings?.takeIf { it.isFinite() && it > 0 } ?: recipe?.servings?.amount ?: 1.0
    val factor = recipe?.let { FoodEngine.factor(it, servings) } ?: 1.0
    DisposableEffect(cooking, snapshot[Flags.foodKeepAwake]) {
        bridge.keepAwake(cooking && snapshot[Flags.foodKeepAwake])
        onDispose { bridge.keepAwake(false); viewModel.flush() }
    }
    Scaffold(topBar = { BackTopBar(recipe?.title.orEmpty(), onBack) {
        if (!cooking) ActionIcon(Res.drawable.ic_edit, Res.string.ic_edit_label) { onEdit() }
        Box {
            ActionIcon(Res.drawable.ic_more_vert, Res.string.action_more) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(Res.string.food_settings)) }, { menu = false; onSettings() })
                DropdownMenuItem({ Text(stringResource(Res.string.food_reset_checks)) }, { menu = false; viewModel.update { it.copy(checkedIngredients = emptySet(), checkedSteps = emptySet(), currentStep = 0) } })
                if (recipe != null) {
                    DropdownMenuItem({ Text(stringResource(if (recipe.pinned) Res.string.action_unpin else Res.string.action_pin)) }, { menu = false; scope.launch { repository.update(id) { it.withOrganisation(pinned = !it.pinned) } } })
                    DropdownMenuItem({ Text(stringResource(Res.string.action_color)) }, { menu = false; colors = true })
                    DropdownMenuItem({ Text(stringResource(if (recipe.archived) Res.string.action_unarchive else Res.string.action_archive)) }, { menu = false; scope.launch { repository.update(id) { it.withOrganisation(archived = !it.archived) }; onBack() } })
                    DropdownMenuItem({ Text(stringResource(Res.string.action_share)) }, { menu = false; scope.launch { try {
                        val ingredients = recipe.ingredientGroups.flatMap { it.items }.joinToString("\n") { FoodEngine.display(it, factor, policy).text() }
                        bridge.shareText(recipe.title + "\n\n" + ingredients + "\n\n" + recipe.steps.joinToString("\n\n") { FoodEngine.stepText(it.text, policy) })
                    } catch (e: Exception) { problem = e.message } } })
                }
            }
        }
    } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (recipe == null) {
            Column(Modifier.padding(padding).padding(20.dp)) {
                if (!viewModel.loaded) LinearProgressIndicator() else Text(stringResource(Res.string.food_recipe_missing))
                viewModel.error?.let { Text(stringResource(Res.string.status_error, it)) }
            }
        } else if (cooking) {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ActiveTimers(recipe, state, viewModel::cancelTimer)
                if (recipe.steps.isEmpty()) Text(stringResource(Res.string.food_no_steps))
                else {
                    val index = state.currentStep.coerceIn(recipe.steps.indices)
                    val step = recipe.steps[index]
                    Text(stringResource(Res.string.food_step_counter, index + 1, recipe.steps.size), style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        step.section?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
                        Text(if (snapshot[Flags.foodStepConversions]) FoodEngine.stepText(step.text, policy) else step.text, style = MaterialTheme.typography.headlineSmall)
                        if (snapshot[Flags.foodTimers]) StepTimerControls(step, state, viewModel::startTimer, viewModel::cancelTimer)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(step.id in state.checkedSteps, { done -> viewModel.update { it.copy(checkedSteps = if (done) it.checkedSteps + step.id!! else it.checkedSteps - step.id!!) } })
                            Text(stringResource(Res.string.food_step_done))
                        }
                        if (snapshot[Flags.foodFanHint]) Text(stringResource(Res.string.food_fan_explain), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({ viewModel.update { it.copy(currentStep = index - 1) } }, enabled = index > 0, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.food_previous)) }
                        Button({ viewModel.update { it.copy(currentStep = index + 1) } }, enabled = index < recipe.steps.lastIndex, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.food_next)) }
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (recipe.images.isNotEmpty()) item { AttachmentList(recipe.images) }
            item { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(recipe.title, style = MaterialTheme.typography.headlineMedium)
                if (recipe.description.isNotBlank()) Text(recipe.description)
                listOf(Res.string.food_prep to recipe.prepMinutes, Res.string.food_cook to recipe.cookMinutes, Res.string.food_total to recipe.totalMinutes).forEach { (label, minutes) ->
                    if (minutes != null) Text(stringResource(label) + ": " + minutes, style = MaterialTheme.typography.bodySmall)
                }
                if (recipe.rating != null) Text("${recipe.rating}/5", style = MaterialTheme.typography.labelLarge)
            } }
            stickyHeader {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton({ viewModel.update { it.copy(servings = maxOf(0.25, servings - if (servings <= 1) 0.25 else 1.0)) } }) { Text("−") }
                            TextButton({ servingsDialog = true }, Modifier.weight(1f)) {
                                AnimatedContent(QuantityFormatter.decimal(servings)) { count -> Text(count + " " + recipe.servings.label.ifBlank { stringResource(Res.string.food_servings) }, style = MaterialTheme.typography.titleMedium) }
                            }
                            OutlinedButton({ viewModel.update { it.copy(servings = servings + if (servings < 1) 0.25 else 1.0) } }) { Text("+") }
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            UnitMode.entries.forEachIndexed { index, option ->
                                SegmentedButton(mode == option, { viewModel.update { it.copy(units = option.wire) } }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(unitLabel(option)) }
                            }
                        }
                    }
                }
            }
            item { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ scaleDialog = true }) { Text(stringResource(Res.string.food_scale_by)) }
                    TextButton({ viewModel.update { it.copy(servings = null) } }) { Text(stringResource(Res.string.food_scale_reset)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5 to Res.string.food_scale_half, 2.0 to Res.string.food_scale_double, 3.0 to Res.string.food_scale_triple).forEach { (multiple, label) ->
                        FilterChip(selected = factor == multiple,
                            onClick = { viewModel.update { it.copy(servings = recipe.servings.amount * multiple) } },
                            label = { Text(stringResource(label)) })
                    }
                }
                if (factor != 1.0) Text(stringResource(Res.string.food_times_unchanged), style = MaterialTheme.typography.bodySmall)
                Button(onCook, Modifier.fillMaxWidth(), enabled = recipe.steps.isNotEmpty()) { Text(stringResource(Res.string.food_cook_mode)) }
                OutlinedButton({ shopDialog = true }, Modifier.fillMaxWidth(), enabled = recipe.ingredientGroups.any { it.items.isNotEmpty() }) { Text(stringResource(Res.string.food_add_to_list)) }
                Text(stringResource(Res.string.food_ingredients), style = MaterialTheme.typography.titleLarge)
            } }
            recipe.ingredientGroups.forEach { group ->
                group.name?.let { heading -> item { Text(heading, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) } }
                items(group.items, key = { it.id!! }) { ingredient ->
                    val display = FoodEngine.display(ingredient, factor, policy)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val checked = ingredient.id in state.checkedIngredients
                        if (snapshot[Flags.foodIngredientChecks]) Checkbox(checked, { done ->
                            viewModel.update { it.copy(checkedIngredients = if (done) it.checkedIngredients + ingredient.id!! else it.checkedIngredients - ingredient.id!!) }
                        })
                        Column(Modifier.weight(1f)) {
                            Text(display.text(), style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (checked) TextDecoration.LineThrough else null))
                            if (ingredient.optional) Text(stringResource(Res.string.food_optional), style = MaterialTheme.typography.labelSmall)
                            if (display.fractionalCount) Text(stringResource(Res.string.food_count_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (recipe.ingredientGroups.flatMap { it.items }.any { FoodEngine.display(it, factor, policy).approximate }) item {
                Text(stringResource(Res.string.food_density_hint), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Text(stringResource(Res.string.food_steps), Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
            items(recipe.steps, key = { it.id!! }) { step ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    step.section?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    Text(if (snapshot[Flags.foodStepConversions]) FoodEngine.stepText(step.text, policy) else step.text)
                    if (snapshot[Flags.foodTimers]) StepTimerControls(step, state, viewModel::startTimer, viewModel::cancelTimer)
                }
            }
            if (recipe.notes.isNotBlank()) item { Text(recipe.notes, Modifier.padding(20.dp)) }
            if (recipe.nutrition.isNotEmpty()) item {
                Column(Modifier.padding(20.dp)) { Text(stringResource(Res.string.food_nutrition), style = MaterialTheme.typography.titleMedium); recipe.nutrition.forEach { (name, value) -> Text("$name: $value") } }
            }
            recipe.sourceUrl?.let { url -> item { TextButton({ try { if (url.startsWith("https://") || url.startsWith("http://")) uriHandler.openUri(url) } catch (e: Exception) { problem = e.message } }, Modifier.padding(horizontal = 12.dp)) {
                Text(recipe.sourceName ?: url)
            } } }
            if (snapshot[Flags.foodFanHint]) item { Text(stringResource(Res.string.food_fan_explain), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (servingsDialog && recipe != null) TextEntryDialog(Res.string.food_servings, QuantityFormatter.decimal(servings), onDismiss = { servingsDialog = false }) { text ->
        val value = AmountParser.parse(text)
        if (value != null && value.min == value.max && value.min > 0) viewModel.update { it.copy(servings = value.min) }
        else problem = numberError
    }
    if (scaleDialog && recipe != null) ScaleByIngredientDialog(recipe, { scaleDialog = false }) { ratio -> viewModel.update { it.copy(servings = recipe.servings.amount * ratio) } }
    if (shopDialog && recipe != null) AddToShoppingDialog(recipe, factor, { shopDialog = false }) { before, after, listId ->
        scope.launch {
            if (snackbar.showSnackbar(getString(Res.string.food_added), getString(Res.string.action_undo)) == SnackbarResult.ActionPerformed) {
                repository.update(listId) { current -> if (current is ShoppingListDocument) current.copy(items = ShoppingLists.undoAdd(before, after, current.items)) else current }
            }
        }
    }
    if (colors) ColorDialog({ colors = false }) { color -> scope.launch { repository.update(id) { it.withOrganisation(color = color) } } }
    (problem ?: viewModel.error)?.let { message -> AlertDialog(onDismissRequest = { problem = null; viewModel.clearError() }, text = { Text(stringResource(Res.string.status_error, message)) },
        confirmButton = { TextButton({ problem = null; viewModel.clearError() }) { Text(stringResource(Res.string.action_close)) } }) }
}


@Composable internal fun unitLabel(mode: UnitMode): String = stringResource(when (mode) {
    UnitMode.Original -> Res.string.food_original; UnitMode.Metric -> Res.string.food_metric; UnitMode.US -> Res.string.food_us
})

@Composable
private fun StepTimerControls(step: RecipeStep, state: RecipeViewState, start: (String, Long) -> Unit, cancel: (String) -> Unit) {
    val hints = remember(step.text, step.timerSeconds) { step.timerSeconds?.takeIf { it in 1..604800 }?.let { listOf(TimerHint(durationText(it), it)) } ?: StepTimers.find(step.text) }
    hints.forEachIndexed { index, hint ->
        val key = "${step.id}:$index"
        val end = state.timerEnds[key]
        if (end == null) OutlinedButton({ start(key, hint.seconds) }) { Text(stringResource(Res.string.food_timer_start, hint.text)) }
        else Row(verticalAlignment = Alignment.CenterVertically) {
            TimerRemaining(end)
            TextButton({ cancel(key) }) { Text(stringResource(Res.string.food_timer_cancel)) }
        }
    }
}

@Composable private fun ActiveTimers(recipe: RecipeDocument, state: RecipeViewState, cancel: (String) -> Unit) {
    state.timerEnds.forEach { (key, end) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val step = recipe.steps.indexOfFirst { it.id == key.substringBeforeLast(':') }
            Text(if (step >= 0) stringResource(Res.string.food_step_label, step + 1) else stringResource(Res.string.food_timer), style = MaterialTheme.typography.labelMedium)
            TimerRemaining(end)
            TextButton({ cancel(key) }) { Text(stringResource(Res.string.action_cancel)) }
        }
    }
}
@Composable private fun TimerRemaining(end: Long) {
    val now by produceState(Clock.System.now().toEpochMilliseconds(), end) {
        while (true) { value = Clock.System.now().toEpochMilliseconds(); delay(1000) }
    }
    val seconds = ((end - now + 999) / 1000).coerceAtLeast(0)
    Text(if (seconds == 0L) stringResource(Res.string.food_timer_done) else stringResource(Res.string.food_timer_left, durationText(seconds)), color = MaterialTheme.colorScheme.primary)
}
private fun durationText(seconds: Long): String = if (seconds >= 3600) "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2,'0')}:${(seconds % 60).toString().padStart(2,'0')}"
    else "${seconds / 60}:${(seconds % 60).toString().padStart(2,'0')}"

@Composable
private fun ScaleByIngredientDialog(recipe: RecipeDocument, dismiss: () -> Unit, apply: (Double) -> Unit) {
    val choices = recipe.ingredientGroups.flatMap { it.items }.filter { it.scalable && it.quantity?.let { q -> q.min == q.max && q.min > 0 } == true }
    var selected by remember { mutableStateOf(choices.firstOrNull()?.id) }
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.food_scale_by)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            choices.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == item.id, { selected = item.id }); Text(item.name) } }
            OutlinedTextField(text, { text = it }, label = { Text(stringResource(Res.string.food_available)) })
            if (problem || choices.isEmpty()) Text(stringResource(Res.string.food_scale_exact), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton({
            try {
                val ingredient = choices.first { it.id == selected }
                val amount = requireNotNull(AmountParser.prefix(text))
                val unit = if (amount.remainder.isBlank()) ingredient.unit else Units.find(amount.remainder)?.id ?: amount.remainder
                apply(FoodEngine.scaleFromIngredient(ingredient, amount.quantity, unit)); dismiss()
            } catch (e: Exception) { problem = true }
        }, enabled = choices.isNotEmpty()) { Text(stringResource(Res.string.action_done)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.action_cancel)) } })
}

@Composable
private fun AddToShoppingDialog(recipe: RecipeDocument, factor: Double, dismiss: () -> Unit, onAdded: (List<ShoppingItem>, List<ShoppingItem>, String) -> Unit) {
    val repository: DocumentRepository = koinInject()
    val tx: Transactor = koinInject()
    val lists by remember { repository.observeList(DocumentType.ShoppingList, false) }.collectAsStateWithLifecycle(emptyList())
    val items = recipe.ingredientGroups.flatMap { it.items }
    var selected by remember { mutableStateOf(items.filterNot { it.optional }.mapNotNull { it.id }.toSet()) }
    var listId by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val defaultTitle = stringResource(Res.string.shopping_default_title)
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.food_choose_ingredients)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            if (lists.isEmpty()) Text(stringResource(Res.string.food_no_lists))
            else lists.forEach { list -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(list.id == (listId ?: lists.first().id), { listId = list.id }); Text(list.title)
            } }
            items.forEach { ingredient -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ingredient.id in selected, { checked -> selected = if (checked) selected + ingredient.id!! else selected - ingredient.id!! })
                Text(FoodEngine.display(ingredient, factor, FoodPolicy(UnitMode.Original)).text())
            } }
            problem?.let { Text(stringResource(Res.string.status_error, it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton({ scope.launch { try {
            var before = emptyList<ShoppingItem>(); var after = emptyList<ShoppingItem>()
            val target = tx.write {
                val id = listId ?: lists.firstOrNull()?.id ?: repository.save(ShoppingListDocument(title = defaultTitle))
                repository.update(id) { current ->
                    if (current !is ShoppingListDocument) current else {
                        before = current.items
                        after = ShoppingLists.add(before, ShoppingLists.fromRecipe(recipe, factor, selected))
                        current.copy(items = after)
                    }
                }; id
            }
            onAdded(before, after, target); dismiss()
        } catch (e: Exception) { problem = e.message } } }, enabled = selected.isNotEmpty()) { Text(stringResource(Res.string.food_add_to_list)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.action_cancel)) } })
}
