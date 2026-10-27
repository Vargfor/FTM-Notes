package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.data.TrainingService
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.feature.food.NumberEntry
import io.github.vargfor.ftmnotes.feature.food.MoveControls
import io.github.vargfor.ftmnotes.feature.food.moveItem
import io.github.vargfor.ftmnotes.feature.settings.ChoiceRow
import io.github.vargfor.ftmnotes.feature.settings.SwitchRow
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.*

@Composable
fun TrainingEditorScreen(id: String, type: String, onBack: () -> Unit,
    viewModel: TrainingEditorViewModel = koinViewModel { parametersOf(id, type) }) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }
    val training: TrainingService = koinInject()
    val catalog by training.exercises.collectAsStateWithLifecycle(emptyList())
    val routines by training.templates.collectAsStateWithLifecycle(emptyList())
    val draft = viewModel.draft
    val scope = rememberCoroutineScope()
    var pickExercise by remember { mutableStateOf<((ExerciseDocument) -> Unit)?>(null) }
    var pickRoutine by remember { mutableStateOf<((WorkoutTemplateDocument) -> Unit)?>(null) }
    var advanced by rememberSaveable(id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        val doc = viewModel.draft as? WorkoutTemplateDocument ?: return@rememberReorderableLazyListState
        val source = doc.blocks.indexOfFirst { it.id == from.key }
        val target = doc.blocks.indexOfFirst { it.id == to.key }
        if (source >= 0 && target >= 0) viewModel.edit(doc.copy(blocks = doc.blocks.toMutableList().apply { add(target, removeAt(source)) }), "order")
    }
    val title = stringResource(when(draft) { is ExerciseDocument -> Res.string.training_new_exercise; is TrainingPlanDocument -> Res.string.training_new_plan; else -> Res.string.training_new_routine })
    Scaffold(topBar = { BackTopBar(title, { viewModel.flush(); onBack() }) {
        ActionIcon(Res.drawable.ic_undo, Res.string.action_undo, viewModel.canUndo) { viewModel.undo() }
        ActionIcon(Res.drawable.ic_redo, Res.string.action_redo, viewModel.canRedo) { viewModel.redo() }
        ActionIcon(Res.drawable.ic_check, Res.string.action_done) { scope.launch { if (viewModel.flushNow()) onBack() } }
    } }) { padding ->
        if (!viewModel.loaded) LinearProgressIndicator(Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(draft.title, { value -> viewModel.edit(when(draft) {
                    is ExerciseDocument -> draft.copy(title = value)
                    is WorkoutTemplateDocument -> draft.copy(title = value)
                    is TrainingPlanDocument -> draft.copy(title = value)
                    else -> draft
                }, "title") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_title)) })
            }
            item { OutlinedTextField(draft.tags.joinToString(", "), { value -> viewModel.edit(draft.withOrganisation(tags = value.split(',').map(String::trim).filter(String::isNotBlank)), "tags") },
                Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_tags)) }) }
            when (draft) {
                is ExerciseDocument -> {
                    item { ExerciseOption(stringResource(Res.string.exercise_category), exerciseCategories + listOf(draft.category), draft.category.takeIf(String::isNotBlank), emptyLabel = stringResource(Res.string.exercise_uncategorized)) { viewModel.edit(draft.copy(category = it.orEmpty())) } }
                    item { ChoiceRow(stringResource(Res.string.training_tracking), TrackingMode.entries, draft.tracking, { trackingLabel(it) }, help = Res.string.help_tracking) { viewModel.edit(draft.copy(tracking = it)) } }
                    item { OutlinedTextField(draft.equipment, { viewModel.edit(draft.copy(equipment = it), "equipment") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_equipment)) }) }
                    item { OutlinedTextField(draft.muscleGroups.joinToString(", "), { viewModel.edit(draft.copy(muscleGroups = it.split(',').map(String::trim).filter(String::isNotBlank)), "muscles") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_muscles)) }) }
                    item { NumberEntry(Res.string.training_rest_seconds, draft.defaultRestSeconds.toDouble(), allowBlank = false, allowZero = true, integer = true) { it?.let { viewModel.edit(draft.copy(defaultRestSeconds = it.toInt()), "rest") } } }
                    item { NotesEntry(draft.notes) { viewModel.edit(draft.copy(notes = it), "notes") } }
                    item { OutlinedButton({ advanced = !advanced }) { Text(stringResource(if (advanced) Res.string.exercise_hide_advanced else Res.string.exercise_advanced)) } }
                    if (advanced) {
                    item { OutlinedTextField(draft.aliases.joinToString(", "), { viewModel.edit(draft.copy(aliases = it.split(',').map(String::trim).filter(String::isNotBlank)), "aliases") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_aliases)) }, trailingIcon = { HelpIcon(stringResource(Res.string.training_aliases), Res.string.help_aliases) }) }
                    item { NumberEntry(Res.string.training_increment, draft.increment, allowBlank = false, help = Res.string.help_increment) { it?.let { viewModel.edit(draft.copy(increment = it), "increment") } } }
                    item { ChoiceRow(stringResource(Res.string.training_unit), listOf<MassUnit?>(null) + MassUnit.entries, draft.unitOverride,
                        { it?.wire ?: stringResource(Res.string.notes_default_color) }, help = Res.string.help_unit_override) { viewModel.edit(draft.copy(unitOverride = it)) } }
                    item { OutlinedTextField(draft.instructionUrl.orEmpty(), { viewModel.edit(draft.copy(instructionUrl = it.ifBlank { null }), "url") }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_instruction_url)) }) }
                    item { CustomFieldDefinitions(draft.fields) { viewModel.edit(draft.copy(fields = it), "fields") } }
                    }
                }
                is WorkoutTemplateDocument -> {
                    item { NotesEntry(draft.notes) { viewModel.edit(draft.copy(notes = it), "notes") } }
                    itemsIndexed(draft.blocks, key = { _, b -> b.id!! }) { index, block ->
                        ReorderableItem(reorder, block.id!!) { _ ->
                            ElevatedCard(Modifier.cardOutline()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(Res.drawable.ic_drag_indicator), stringResource(Res.string.notes_drag), Modifier.size(48.dp).draggableHandle())
                                        Text(block.exercise.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                        MoveControls(index, draft.blocks.size) { delta -> viewModel.edit(draft.copy(blocks = moveItem(draft.blocks, index, delta))) }
                                        ActionIcon(Res.drawable.ic_close, Res.string.action_delete) { viewModel.edit(draft.copy(blocks = draft.blocks.filterNot { it.id == block.id })) }
                                    }
                                    fun change(value: WorkoutBlock, key: String? = null) { viewModel.edit(draft.copy(blocks = draft.blocks.map { if (it.id == block.id) value else it }), key) }
                                    OutlinedButton({ pickExercise = { chosen -> change(block.copy(exercise = ExerciseRef(chosen.id, chosen.title, chosen.tracking, chosen.equipment))) } }) { Text(stringResource(Res.string.training_replace_exercise)) }
                                    OutlinedTextField(block.supersetGroup.orEmpty(), { change(block.copy(supersetGroup = it.ifBlank { null }), "superset/" + block.id) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_superset)) }, trailingIcon = { HelpIcon(stringResource(Res.string.training_superset), Res.string.help_supersets) })
                                    NumberEntry(Res.string.training_rest_seconds, block.restSeconds?.toDouble(), allowZero = true, integer = true) { change(block.copy(restSeconds = it?.toInt()), "rest/" + block.id) }
                                    NotesEntry(block.notes) { change(block.copy(notes = it), "note/" + block.id) }
                                    val exercise = catalog.firstOrNull { it.id == block.exercise.id }
                                    block.sets.forEachIndexed { setIndex, target ->
                                        TargetEditor(target, exercise?.tracking ?: block.exercise.tracking ?: TrackingMode.WeightReps, exercise?.fields.orEmpty(),
                                            onChange = { value -> change(block.copy(sets = block.sets.map { if (it.id == target.id) value else it }), "target/" + target.id) },
                                            onRemove = { change(block.copy(sets = block.sets.filterNot { it.id == target.id })) },
                                            onMove = { delta -> change(block.copy(sets = moveItem(block.sets, setIndex, delta))) }, index = setIndex, size = block.sets.size)
                                    }
                                    OutlinedButton({ change(block.copy(sets = block.sets + (block.sets.lastOrNull() ?: SetTarget(reps = 8)).copy(id = Ids.newId()))) }) { Text(stringResource(Res.string.training_add_set)) }
                                }
                            }
                        }
                    }
                    item { Button({ pickExercise = { chosen ->
                        viewModel.edit(draft.copy(blocks = draft.blocks + WorkoutBlock(Ids.newId(), ExerciseRef(chosen.id, chosen.title, chosen.tracking, chosen.equipment),
                            restSeconds = chosen.defaultRestSeconds, sets = List(3) { SetTarget(Ids.newId(), reps = if (chosen.tracking.hasReps) 8 else null) })))
                    } }, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.training_add_exercise)) } }
                }
                is TrainingPlanDocument -> {
                    item { NumberEntry(Res.string.training_weeks, draft.weeks.toDouble(), allowBlank = false, integer = true) { it?.let { viewModel.edit(draft.copy(weeks = it.toInt()), "weeks") } } }
                    item { NotesEntry(draft.notes) { viewModel.edit(draft.copy(notes = it), "notes") } }
                    item { HelpLabel(stringResource(Res.string.training_days), Res.string.help_plan_days, style = MaterialTheme.typography.titleLarge) }
                    itemsIndexed(draft.days, key = { _, d -> d.id!! }) { index, day ->
                        ElevatedCard(Modifier.cardOutline()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(day.name, { value -> viewModel.edit(draft.copy(days = draft.days.map { if (it.id == day.id) it.copy(name = value) else it }), "day/" + day.id) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_day_name)) })
                                Text(day.template.name)
                                FlowRow {
                                    TextButton({ pickRoutine = { routine -> viewModel.edit(draft.copy(days = draft.days.map { if (it.id == day.id) it.copy(template = TemplateRef(routine.id, routine.title)) else it })) } }) { Text(stringResource(Res.string.training_choose_routine)) }
                                    MoveControls(index, draft.days.size) { delta -> viewModel.edit(draft.copy(days = moveItem(draft.days, index, delta))) }
                                    ActionIcon(Res.drawable.ic_close, Res.string.action_delete) { viewModel.edit(draft.copy(days = draft.days.filterNot { it.id == day.id })) }
                                }
                            }
                        }
                    }
                    item { OutlinedButton({ pickRoutine = { routine -> viewModel.edit(draft.copy(days = draft.days + PlanDay(Ids.newId(), routine.title, TemplateRef(routine.id, routine.title)))) } }) { Text(stringResource(Res.string.training_add_day)) } }
                    item { RuleEditor(draft.progression) { viewModel.edit(draft.copy(progression = it), "progression") } }
                    item { NumberEntry(Res.string.training_deload_every, draft.deloadEveryWeeks?.toDouble(), integer = true, help = Res.string.help_deload) { viewModel.edit(draft.copy(deloadEveryWeeks = it?.toInt()), "deload-every") } }
                    item { NumberEntry(Res.string.training_deload_percent, draft.deloadPercent, allowBlank = false, allowZero = true, help = Res.string.help_deload) { it?.let { viewModel.edit(draft.copy(deloadPercent = it), "deload") } } }
                    item { HelpLabel(stringResource(Res.string.training_rule_override), Res.string.help_rule_overrides, style = MaterialTheme.typography.titleLarge) }
                    draft.exerciseRules.forEachIndexed { index, entry -> item(key = "rule/" + index) {
                        ElevatedCard(Modifier.cardOutline()) { Column(Modifier.padding(12.dp)) {
                            Text(entry.exercise.name, style = MaterialTheme.typography.titleMedium)
                            RuleEditor(entry.rule) { rule -> viewModel.edit(draft.copy(exerciseRules = draft.exerciseRules.mapIndexed { i, old -> if (i == index) old.copy(rule = rule) else old }), "rule/" + index) }
                            TextButton({ viewModel.edit(draft.copy(exerciseRules = draft.exerciseRules.filterIndexed { i, _ -> i != index })) }) { Text(stringResource(Res.string.action_delete)) }
                        } }
                    } }
                    item { OutlinedButton({ pickExercise = { exercise -> viewModel.edit(draft.copy(exerciseRules = draft.exerciseRules + ExerciseProgression(ExerciseRef(exercise.id, exercise.title, exercise.tracking, exercise.equipment), draft.progression))) } }) { Text(stringResource(Res.string.training_add_rule)) } }
                    item { HelpLabel(stringResource(Res.string.training_overrides), Res.string.help_week_overrides, style = MaterialTheme.typography.titleLarge) }
                    draft.overrides.forEachIndexed { index, entry -> item(key = "override/" + index) {
                        ElevatedCard(Modifier.cardOutline()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.exercise.name, style = MaterialTheme.typography.titleMedium)
                            fun change(value: WeekOverride) { viewModel.edit(draft.copy(overrides = draft.overrides.mapIndexed { i, old -> if (i == index) value else old }), "override/" + index) }
                            NumberEntry(Res.string.training_plan_week, entry.week.toDouble(), allowBlank = false, integer = true) { it?.let { change(entry.copy(week = it.toInt())) } }
                            ChoiceRow(stringResource(Res.string.training_days), listOf<PlanDay?>(null) + draft.days, draft.days.firstOrNull { it.id == entry.dayId },
                                { it?.name ?: stringResource(Res.string.training_all_days) }, help = Res.string.help_plan_days) { change(entry.copy(dayId = it?.id)) }
                            val exercise = catalog.firstOrNull { it.id == entry.exercise.id }
                            entry.sets.forEachIndexed { setIndex, target -> TargetEditor(target, exercise?.tracking ?: TrackingMode.WeightReps, exercise?.fields.orEmpty(),
                                { value -> change(entry.copy(sets = entry.sets.map { if (it.id == target.id) value else it })) },
                                { change(entry.copy(sets = entry.sets.filterNot { it.id == target.id })) },
                                { delta -> change(entry.copy(sets = moveItem(entry.sets, setIndex, delta))) }, setIndex, entry.sets.size) }
                            OutlinedButton({ change(entry.copy(sets = entry.sets + SetTarget(Ids.newId(), reps = 8))) }) { Text(stringResource(Res.string.training_add_set)) }
                            TextButton({ viewModel.edit(draft.copy(overrides = draft.overrides.filterIndexed { i, _ -> i != index })) }) { Text(stringResource(Res.string.action_delete)) }
                        } }
                    } }
                    item { OutlinedButton({ pickExercise = { exercise -> viewModel.edit(draft.copy(overrides = draft.overrides + WeekOverride(1, exercise = ExerciseRef(exercise.id, exercise.title, exercise.tracking, exercise.equipment), sets = List(3) { SetTarget(Ids.newId(), reps = 8) }))) } }) { Text(stringResource(Res.string.training_add_override)) } }
                }
                else -> Unit
            }
            viewModel.error?.let { item { Text(stringResource(Res.string.status_error, it), color = MaterialTheme.colorScheme.error) } }
        }
    }
    pickExercise?.let { callback -> ExercisePicker(catalog, { pickExercise = null }, callback) }
    pickRoutine?.let { callback -> AlertDialog(onDismissRequest = { pickRoutine = null }, title = { Text(stringResource(Res.string.training_choose_routine)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { routines.filterNot { it.archived }.forEach { r ->
            TextButton({ callback(r); pickRoutine = null }) { Text(r.title) }
        } } }, confirmButton = { TextButton({ pickRoutine = null }) { Text(stringResource(Res.string.action_cancel)) } }) }
}

@Composable internal fun NotesEntry(value: String, change: (String) -> Unit) {
    TrainingTextField(value, change, Res.string.training_notes, minLines = 2)
}

@Composable private fun TargetEditor(target: SetTarget, mode: TrackingMode, fields: List<CustomTrainingField>,
    onChange: (SetTarget) -> Unit, onRemove: () -> Unit, onMove: (Int) -> Unit, index: Int, size: Int) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(border = cardBorder()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ expanded = !expanded }, Modifier.weight(1f).trainingDragHandle(onMove)) { Text((index + 1).toString() + " · " + setKindLabel(target.kind)) }
                MoveControls(index, size, onMove)
                ActionIcon(Res.drawable.ic_close, Res.string.action_delete, onClick = onRemove)
            }
            if (mode.hasReps) NumberEntry(Res.string.training_target_reps, target.reps?.toDouble(), integer = true) { onChange(target.copy(reps = it?.toInt())) }
            if (mode.hasWeight) {
                SwitchRow(stringResource(Res.string.training_load_kind), target.load != null, { onChange(target.copy(load = if (it) LoadTarget() else null)) }, help = Res.string.help_load_targets)
                target.load?.let { load ->
                    NumberEntry(Res.string.training_load_amount, load.amount, allowBlank = false, allowZero = true) { it?.let { onChange(target.copy(load = load.copy(amount = it))) } }
                    ChoiceRow(stringResource(Res.string.training_unit), MassUnit.entries, load.unit, { it.wire }) { onChange(target.copy(load = load.copy(unit = it))) }
                    ChoiceRow(stringResource(Res.string.training_load_kind), LoadKind.entries, load.kind, { loadKindLabel(it) }, help = Res.string.help_load_targets) { onChange(target.copy(load = load.copy(kind = it))) }
                }
            }
            if (mode.hasDuration) NumberEntry(Res.string.training_seconds, target.seconds) { onChange(target.copy(seconds = it)) }
            if (mode.hasDistance) NumberEntry(Res.string.training_metres, target.metres) { onChange(target.copy(metres = it)) }
            if (expanded) {
                ChoiceRow(stringResource(Res.string.training_set), SetKind.entries, target.kind, { setKindLabel(it) }, help = Res.string.help_set_types) { onChange(target.copy(kind = it)) }
                if (mode.hasReps) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberEntry(Res.string.training_min_reps, target.repMin?.toDouble(), Modifier.weight(1f), integer = true) { onChange(target.copy(repMin = it?.toInt(), reps = null)) }
                    NumberEntry(Res.string.training_max_reps, target.repMax?.toDouble(), Modifier.weight(1f), integer = true) { onChange(target.copy(repMax = it?.toInt(), reps = null)) }
                }
            }
            CustomValuesFields(fields, target.customValues) { onChange(target.copy(customValues = it)) }
        }
    }
}

@Composable internal fun RuleEditor(rule: ProgressionRule, change: (ProgressionRule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow(stringResource(Res.string.training_progression), ProgressionKind.entries, rule.kind, { progressionLabel(it) }, help = Res.string.help_progression) { change(rule.copy(kind = it)) }
        if (rule.kind != ProgressionKind.None) {
            ChoiceRow(stringResource(Res.string.training_frequency), ProgressionFrequency.entries, rule.frequency,
                { stringResource(if (it == ProgressionFrequency.Session) Res.string.training_per_session else Res.string.training_per_week) }, help = Res.string.help_frequency) { change(rule.copy(frequency = it)) }
            NumberEntry(Res.string.training_increment, rule.increment, allowBlank = false, help = Res.string.help_progression_increment) { it?.let { change(rule.copy(increment = it)) } }
            ChoiceRow(stringResource(Res.string.training_unit), MassUnit.entries, rule.unit, { it.wire }) { change(rule.copy(unit = it)) }
            if (rule.kind == ProgressionKind.Double) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberEntry(Res.string.training_min_reps, rule.repMin.toDouble(), Modifier.weight(1f), allowBlank = false, integer = true) { it?.let { change(rule.copy(repMin = it.toInt())) } }
                NumberEntry(Res.string.training_max_reps, rule.repMax.toDouble(), Modifier.weight(1f), allowBlank = false, integer = true) { it?.let { change(rule.copy(repMax = it.toInt())) } }
            }
            NumberEntry(Res.string.training_misses, rule.missesBeforeDeload.toDouble(), allowBlank = false, integer = true, help = Res.string.help_deload) { it?.let { change(rule.copy(missesBeforeDeload = it.toInt())) } }
            NumberEntry(Res.string.training_deload_percent, rule.deloadPercent, allowBlank = false, allowZero = true, help = Res.string.help_deload) { it?.let { change(rule.copy(deloadPercent = it)) } }
            if (rule.kind == ProgressionKind.Manual) {
                var text by remember(rule.manualWeeks) { mutableStateOf(rule.manualWeeks.joinToString("\n") { it.week.toString() + "," + number(it.weight) + "," + (it.reps?.toString().orEmpty()) }) }
                var invalid by remember { mutableStateOf(false) }
                OutlinedTextField(text, { value -> text = value
                    try {
                        val weeks = value.lineSequence().filter(String::isNotBlank).map { line ->
                            val parts = line.split(','); WeekTarget(parts[0].trim().toInt(), parts.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)?.toDouble(), parts.getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)?.toInt(), rule.unit)
                        }.toList()
                        change(rule.copy(manualWeeks = weeks)); invalid = false
                    } catch (_: Exception) { invalid = true }
                }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_manual_weeks)) },
                    supportingText = { Text(stringResource(Res.string.training_weekly_hint)) }, isError = invalid, minLines = 3, trailingIcon = { HelpIcon(stringResource(Res.string.training_manual_weeks), Res.string.help_manual_weeks) })
            }
        }
    }
}

@Composable internal fun CustomValuesFields(fields: List<CustomTrainingField>, values: Map<String, JsonElement>, change: (Map<String, JsonElement>) -> Unit) {
    fields.forEach { field ->
        val value = values[field.key] as? JsonPrimitive
        if (field.kind == FieldKind.Boolean) SwitchRow(field.label, value?.booleanOrNull ?: false, { change(values + (field.key to JsonPrimitive(it))) })
        else {
            var text by remember(value) { mutableStateOf(value?.content.orEmpty()) }
            val valid = text.isBlank() || field.kind == FieldKind.Text || text.replace(',', '.').toDoubleOrNull()?.isFinite() == true
            OutlinedTextField(text, { next -> text = next
                if (next.isBlank()) change(values - field.key)
                else if (field.kind == FieldKind.Text) change(values + (field.key to JsonPrimitive(next)))
                else next.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)?.let { change(values + (field.key to JsonPrimitive(it))) }
            }, Modifier.fillMaxWidth(), label = { Text(field.label + if (field.unit.isBlank()) "" else " (" + field.unit + ")") }, isError = !valid)
        }
    }
}

@Composable private fun CustomFieldDefinitions(fields: List<CustomTrainingField>, change: (List<CustomTrainingField>) -> Unit) {
    var text by remember(fields) { mutableStateOf(fields.joinToString("\n") { it.key + " | " + it.label + " | " + it.kind.name.lowercase() + " | " + it.unit }) }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(text, { value -> text = value
        try {
            val parsed = value.lineSequence().filter(String::isNotBlank).map { line ->
                val p = line.split('|').map(String::trim)
                require(p[0].isNotBlank())
                val kind = FieldKind.entries.first { it.name.equals(p.getOrNull(2).orEmpty().ifBlank { "number" }, true) }
                CustomTrainingField(p[0], p.getOrNull(1).orEmpty().ifBlank { p[0] }, kind, p.getOrNull(3).orEmpty())
            }.toList()
            require(parsed.map { it.key }.distinct().size == parsed.size)
            change(parsed); invalid = false
        } catch (_: Exception) { invalid = true }
    }, Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(Res.string.training_custom_fields)) },
        supportingText = { Text(stringResource(Res.string.training_custom_field_hint)) }, isError = invalid, trailingIcon = { HelpIcon(stringResource(Res.string.training_custom_fields), Res.string.help_custom_fields) })
}

