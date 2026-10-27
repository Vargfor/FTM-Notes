package io.github.vargfor.ftmnotes.feature.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.food.engine.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import okio.FileSystem
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToLong

@Composable
fun RecipeEditorScreen(id: String, onBack: () -> Unit, viewModel: RecipeEditorViewModel = koinViewModel { parametersOf(id) }) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }
    val draft = viewModel.draft
    val bridge = koinInject<AppPlatform>().bridge
    val store: AttachmentStore = koinInject()
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    fun photo(camera: Boolean) {
        scope.launch { try {
            val files = if (camera) listOfNotNull(bridge.takePhoto()) else bridge.pickFiles(true)
            val refs = files.map { store.put(FileSystem.SYSTEM.source(it.path), it.mime, it.name) }
            viewModel.edit(viewModel.draft.copy(images = (viewModel.draft.images + refs).distinctBy { it.sha256 }))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { problem = e.message } }
    }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.food_new_recipe), { viewModel.flush(); onBack() }) {
        ActionIcon(Res.drawable.ic_undo, Res.string.action_undo, viewModel.canUndo) { viewModel.undo() }
        ActionIcon(Res.drawable.ic_redo, Res.string.action_redo, viewModel.canRedo) { viewModel.redo() }
        ActionIcon(Res.drawable.ic_check, Res.string.action_done) { scope.launch { if (viewModel.flushNow()) onBack() } }
    } }) { padding ->
        if (!viewModel.loaded) {
            Column(Modifier.padding(padding).padding(20.dp)) {
                LinearProgressIndicator()
                viewModel.error?.let { Text(stringResource(Res.string.status_error, it)) }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(draft.title, { viewModel.edit(draft.copy(title = it), "title") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_recipe_title)) }) }
            item { OutlinedTextField(draft.description, { viewModel.edit(draft.copy(description = it), "description") }, Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(Res.string.food_description)) }) }
            item {
                NumberEntry(Res.string.food_servings, draft.servings.amount, allowBlank = false) { value -> if (value != null) viewModel.edit(draft.copy(servings = draft.servings.copy(amount = value)), "servings") }
                OutlinedTextField(draft.servings.label, { viewModel.edit(draft.copy(servings = draft.servings.copy(label = it)), "serving-label") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_servings_label)) })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberEntry(Res.string.food_prep, draft.prepMinutes?.toDouble(), Modifier.weight(1f), allowZero = true, integer = true) { viewModel.edit(draft.copy(prepMinutes = it?.toInt()), "prep") }
                    NumberEntry(Res.string.food_cook, draft.cookMinutes?.toDouble(), Modifier.weight(1f), allowZero = true, integer = true) { viewModel.edit(draft.copy(cookMinutes = it?.toInt()), "cook") }
                    NumberEntry(Res.string.food_total, draft.totalMinutes?.toDouble(), Modifier.weight(1f), allowZero = true, integer = true) { viewModel.edit(draft.copy(totalMinutes = it?.toInt()), "total") }
                }
            }
            item {
                AttachmentList(draft.images) { sha -> viewModel.edit(draft.copy(images = draft.images.filterNot { it.sha256 == sha })) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ photo(false) }) { Text(stringResource(Res.string.attachment_add)) }
                    if (bridge.canTakePhoto) OutlinedButton({ photo(true) }) { Text(stringResource(Res.string.attachment_camera)) }
                }
            }
            item { Text(stringResource(Res.string.food_ingredients), style = MaterialTheme.typography.titleLarge) }
            draft.ingredientGroups.forEachIndexed { groupIndex, group ->
                item(key = "group-${group.id}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(group.name.orEmpty(), { value ->
                            viewModel.edit(draft.copy(ingredientGroups = draft.ingredientGroups.map { if (it.id == group.id) it.copy(name = value.ifBlank { null }) else it }), "group/${group.id}")
                        }, Modifier.weight(1f), label = { Text(stringResource(Res.string.food_group_name)) })
                        MoveControls(groupIndex, draft.ingredientGroups.size, { delta -> viewModel.edit(draft.copy(ingredientGroups = moveItem(draft.ingredientGroups, groupIndex, delta))) })
                        ActionIcon(Res.drawable.ic_close, Res.string.action_delete) { viewModel.edit(draft.copy(ingredientGroups = draft.ingredientGroups.filterNot { it.id == group.id })) }
                    }
                }
                itemsIndexed(group.items, key = { _, it -> it.id!! }) { index, ingredient ->
                    IngredientEditor(ingredient,
                        onChange = { next, key -> viewModel.ingredient(group.id!!, ingredient.id!!, { next }, key) },
                        onDelete = { viewModel.edit(draft.copy(ingredientGroups = draft.ingredientGroups.map { if (it.id == group.id) it.copy(items = it.items.filterNot { item -> item.id == ingredient.id }) else it })) },
                        onMove = { delta -> viewModel.edit(draft.copy(ingredientGroups = draft.ingredientGroups.map { if (it.id == group.id) it.copy(items = moveItem(it.items, index, delta)) else it })) },
                        index = index, size = group.items.size)
                }
                item(key = "add-${group.id}") { TextButton({
                    viewModel.edit(draft.copy(ingredientGroups = draft.ingredientGroups.map { if (it.id == group.id) it.copy(items = it.items + Ingredient(id = Ids.newId())) else it }))
                }) { Text(stringResource(Res.string.food_add_ingredient)) } }
            }
            item { OutlinedButton({ viewModel.edit(draft.copy(ingredientGroups = draft.ingredientGroups + IngredientGroup(id = Ids.newId()))) }) { Text(stringResource(Res.string.food_add_group)) } }
            item { Text(stringResource(Res.string.food_steps), style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(draft.steps, key = { _, it -> it.id!! }) { index, step ->
                ElevatedCard(Modifier.cardOutline()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(step.text, { text -> viewModel.edit(draft.copy(steps = draft.steps.map { if (it.id == step.id) it.copy(text = text) else it }), "step/${step.id}") },
                            Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(Res.string.food_step)) })
                        OutlinedTextField(step.section.orEmpty(), { text -> viewModel.edit(draft.copy(steps = draft.steps.map { if (it.id == step.id) it.copy(section = text.ifBlank { null }) else it }), "section/${step.id}") },
                            Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_step_section)) })
                        NumberEntry(Res.string.food_timer_minutes, step.timerSeconds?.div(60.0)) { minutes ->
                            viewModel.edit(draft.copy(steps = draft.steps.map { if (it.id == step.id) it.copy(timerSeconds = minutes?.let { (it * 60).roundToLong().coerceIn(1,604800) }) else it }), "timer/${step.id}")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MoveControls(index, draft.steps.size, { delta -> viewModel.edit(draft.copy(steps = moveItem(draft.steps, index, delta))) })
                            ActionIcon(Res.drawable.ic_close, Res.string.action_delete) { viewModel.edit(draft.copy(steps = draft.steps.filterNot { it.id == step.id })) }
                        }
                    }
                }
            }
            item { OutlinedButton({ viewModel.edit(draft.copy(steps = draft.steps + RecipeStep(id = Ids.newId()))) }) { Text(stringResource(Res.string.food_add_step)) } }
            item { OutlinedTextField(draft.notes, { viewModel.edit(draft.copy(notes = it), "notes") }, Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(Res.string.food_notes)) }) }
            item { OutlinedTextField(draft.tags.joinToString(", "), { viewModel.edit(draft.copy(tags = it.split(',').map(String::trim)), "tags") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.action_labels)) }) }
            item { OutlinedTextField(draft.sourceUrl.orEmpty(), { viewModel.edit(draft.copy(sourceUrl = it.ifBlank { null }), "url") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_source_url)) }) }
            item { OutlinedTextField(draft.sourceName.orEmpty(), { viewModel.edit(draft.copy(sourceName = it.ifBlank { null }), "source") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_source_name)) }) }
            item { OutlinedTextField(draft.nutrition.entries.joinToString("\n") { "${it.key}: ${it.value}" }, { text ->
                val entries = text.lines().filter(String::isNotBlank).mapIndexed { i, line -> if (':' in line) line.substringBefore(':').trim() to line.substringAfter(':').trim() else "info-$i" to line }.toMap()
                viewModel.edit(draft.copy(nutrition = entries), "nutrition")
            }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_nutrition)) }, supportingText = { Text(stringResource(Res.string.food_nutrition_hint)) }) }
            item {
                Text(stringResource(Res.string.food_rating), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(draft.rating == null, { viewModel.edit(draft.copy(rating = null)) }, label = { Text(stringResource(Res.string.food_unrated)) })
                    (1..5).forEach { rating -> FilterChip(draft.rating == rating, { viewModel.edit(draft.copy(rating = rating)) }, label = { Text("$rating/5") }) }
                }
            }
            (problem ?: viewModel.error)?.let { message -> item { Text(stringResource(Res.string.status_error, message), color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun IngredientEditor(
    ingredient: Ingredient, onChange: (Ingredient, String?) -> Unit, onDelete: () -> Unit,
    onMove: (Int) -> Unit, index: Int, size: Int,
) {
    var expanded by remember(ingredient.id) { mutableStateOf(false) }
    ElevatedCard(Modifier.cardOutline()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ingredient.raw.ifBlank { FoodEngine.display(ingredient, policy = FoodPolicy(UnitMode.Original)).text() },
                { text -> onChange(IngredientParser.parse(text).copy(id = ingredient.id), "ingredient/${ingredient.id}") },
                Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_ingredient_line)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ expanded = !expanded }) { Text(stringResource(Res.string.food_ingredient_fields)) }
                MoveControls(index, size, onMove)
                ActionIcon(Res.drawable.ic_close, Res.string.action_delete, onClick = onDelete)
            }
            if (expanded) {
                OutlinedTextField(ingredient.name, { onChange(ingredient.copy(name = it, raw = ""), "name/${ingredient.id}") },
                    Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_name)) })
                QuantityEntry(ingredient.quantity) { onChange(ingredient.copy(quantity = it, raw = ""), "quantity/${ingredient.id}") }
                OutlinedTextField(ingredient.unit.orEmpty(), { onChange(ingredient.copy(unit = Units.find(it)?.id ?: it.ifBlank { null }, raw = ""), "unit/${ingredient.id}") },
                    Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_unit)) })
                OutlinedTextField(ingredient.note, { onChange(ingredient.copy(note = it, raw = ""), "note/${ingredient.id}") },
                    Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_note)) })
                OutlinedTextField(ingredient.category, { onChange(ingredient.copy(category = it), "category/${ingredient.id}") },
                    Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_category)) })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ingredient.optional, { onChange(ingredient.copy(optional = it), null) }); Text(stringResource(Res.string.food_optional)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ingredient.scalable, { onChange(ingredient.copy(scalable = it), null) }); Text(stringResource(Res.string.food_scalable)) }
            }
        }
    }
}

@Composable
internal fun NumberEntry(label: StringResource, value: Double?, modifier: Modifier = Modifier, allowBlank: Boolean = true, allowZero: Boolean = false, integer: Boolean = false, help: StringResource? = null, onChange: (Double?) -> Unit) {
    var text by remember { mutableStateOf(value?.let { QuantityFormatter.decimal(it) }.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value?.let { QuantityFormatter.decimal(it) }.orEmpty() }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = if (text.isBlank()) allowBlank else parsed != null && parsed.isFinite() && (if (allowZero) parsed >= 0 else parsed > 0) && (!integer || parsed % 1 == 0.0)
    OutlinedTextField(text, { next ->
        text = next
        val number = next.replace(',', '.').toDoubleOrNull()
        if (next.isBlank() && allowBlank) onChange(null)
        else if (number != null && number.isFinite() && (if (allowZero) number >= 0 else number > 0) && (!integer || number % 1 == 0.0)) onChange(number)
    }, modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, singleLine = true, label = { Text(stringResource(label)) }, isError = !valid,
        trailingIcon = help?.let { body -> { HelpIcon(stringResource(label), body) } },
        supportingText = if (!valid) { { Text(stringResource(Res.string.food_positive_number)) } } else null)
}

@Composable
internal fun QuantityEntry(value: Quantity?, onChange: (Quantity?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { QuantityFormatter.format(it) }.orEmpty()) }
    val valid = text.isBlank() || AmountParser.parse(text) != null
    OutlinedTextField(text, { next -> text = next; if (next.isBlank()) onChange(null) else AmountParser.parse(next)?.let(onChange) },
        Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_quantity)) }, isError = !valid,
        supportingText = if (!valid) { { Text(stringResource(Res.string.food_invalid_quantity)) } } else null)
}

@Composable
internal fun MoveControls(index: Int, size: Int, onMove: (Int) -> Unit) {
    ActionIcon(Res.drawable.ic_expand_more, Res.string.action_move_up, index > 0) { onMove(-1) }
    ActionIcon(Res.drawable.ic_expand_more, Res.string.action_move_down, index + 1 < size) { onMove(1) }
}
internal fun <T> moveItem(items: List<T>, index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in items.indices || target !in items.indices) return items
    return items.toMutableList().apply { add(target, removeAt(index)) }
}
