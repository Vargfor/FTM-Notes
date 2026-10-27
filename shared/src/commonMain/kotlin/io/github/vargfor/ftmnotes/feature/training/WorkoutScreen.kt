package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.time.Instant
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.feature.food.NumberEntry
import io.github.vargfor.ftmnotes.feature.food.MoveControls
import io.github.vargfor.ftmnotes.feature.food.moveItem
import io.github.vargfor.ftmnotes.feature.settings.ChoiceRow
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.*
import kotlin.time.Clock

private data class KeypadEntry(val label: String, val value: Double?, val step: Double = 1.0,
    val integer: Boolean = false, val min: Double = 0.0, val max: Double = Double.MAX_VALUE, val save: (Double?) -> Unit)

@Composable
fun WorkoutScreen(id: String, onBack: () -> Unit, onFinished: (String) -> Unit,
    viewModel: WorkoutViewModel = koinViewModel { parametersOf(id) }) {
    val training = viewModel.training
    val settings by koinInject<SettingsRepository>().snapshot.collectAsStateWithLifecycle()
    val bridge = koinInject<AppPlatform>().bridge
    val active by training.active.collectAsStateWithLifecycle()
    val catalog by training.exercises.collectAsStateWithLifecycle(emptyList())
    val history by training.sessions.collectAsStateWithLifecycle(emptyList())
    val session = viewModel.session
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var keypad by remember { mutableStateOf<KeypadEntry?>(null) }
    var pick by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    var finish by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var plates by remember { mutableStateOf<Pair<Double, MassUnit>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    val repsLabel = stringResource(Res.string.training_reps)
    val isActive = session?.status == SessionStatus.InProgress && active?.session?.id == id
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }
    DisposableEffect(viewModel, isActive, settings[Flags.trainingKeepAwake]) {
        bridge.keepAwake(isActive && settings[Flags.trainingKeepAwake])
        onDispose { viewModel.flush(); bridge.keepAwake(false) }
    }
    LaunchedEffect(isActive) {
        if (isActive) bridge.requestWorkoutNotifications()
        while (isActive) { now = Clock.System.now().toEpochMilliseconds(); delay(1000) }
    }
    val focus by training.focusExercise.collectAsStateWithLifecycle()
    LaunchedEffect(focus) {
        val index = session?.exercises?.indexOfFirst { it.id == focus } ?: -1
        if (index >= 0) list.animateScrollToItem(index + if (isActive) 2 else 1)
    }
    fun change(transform: (WorkoutSessionDocument) -> WorkoutSessionDocument) { viewModel.action { training.mutate(id, transform) } }
    fun exerciseChange(e: SessionExercise, transform: (SessionExercise) -> SessionExercise) {
        change { current -> current.copy(exercises = current.exercises.map { if (it.id == e.id) transform(it) else it }) }
    }
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        val current = viewModel.session ?: return@rememberReorderableLazyListState
        val a = current.exercises.indexOfFirst { it.id == from.key }; val b = current.exercises.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) change { it.copy(exercises = moveItem(it.exercises, a, b - a)) }
    }
    Scaffold(topBar = { BackTopBar(session?.title.orEmpty(), { viewModel.flush(); onBack() }) {
        ActionIcon(Res.drawable.ic_undo, Res.string.action_undo, viewModel.canUndo) { viewModel.undo() }
        ActionIcon(Res.drawable.ic_redo, Res.string.action_redo, viewModel.canRedo) { viewModel.redo() }
        if (isActive) TextButton({ finish = true }) { Text(stringResource(Res.string.training_finish)) }
        else ActionIcon(Res.drawable.ic_check, Res.string.action_done) { scope.launch { if (viewModel.flushNow()) onBack() } }
    } }) { padding ->
        if (!viewModel.loaded) LinearProgressIndicator(Modifier.padding(padding))
        else if (session != null) LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrainingTextField(session.title, { title -> change { it.copy(title = title) } }, Res.string.training_title)
                    val totals = TrainingEngine.totals(session, settings[Flags.trainingIncludeWarmups])
                    val seconds = if (session.endedAt == null) ((now - (session.startedAt?.toEpochMilliseconds() ?: now)) / 1000) else totals.seconds
                    Text(stringResource(Res.string.training_total_sets, totals.sets) + " · " + duration(seconds) + " · " +
                        stringResource(Res.string.training_volume, number(TrainingEngine.mass(totals.volumeKg, MassUnit.Kg, settings[Flags.trainingUnits])), settings[Flags.trainingUnits].wire))
                    if (settings[Flags.trainingBodyweight]) NumberEntry(Res.string.training_bodyweight,
                        session.bodyweight?.let { TrainingEngine.mass(it, session.bodyweightUnit, settings[Flags.trainingUnits]) }) { value ->
                        change { it.copy(bodyweight = value, bodyweightUnit = settings[Flags.trainingUnits]) }
                    }
                }
            }
            if (isActive) item {
                val state = active?.state
                if (state?.restEnd != null) {
                    val millis = TrainingService.remainingMillis(state, now, bridge.elapsedRealtimeMillis(), bridge.bootId())
                    Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(if (millis <= 0) stringResource(Res.string.training_timer_finished) else stringResource(Res.string.training_timer_remaining, duration((millis + 999) / 1000)), style = MaterialTheme.typography.titleLarge)
                        Row {
                            TextButton({ scope.launch { training.extendRest(15) } }) { Text(stringResource(Res.string.training_extend_rest)) }
                            TextButton({ scope.launch { training.skipRest() } }) { Text(stringResource(Res.string.training_skip_rest)) }
                        }
                    } }
                } else OutlinedButton({ scope.launch { training.startRest(null, settings[Flags.trainingRestSeconds].coerceAtLeast(1)) } }) { Text(stringResource(Res.string.training_rest)) }
            }
            itemsIndexed(session.exercises, key = { _, e -> e.id!! }) { _, e ->
                ReorderableItem(reorder, key = e.id!!) {
                    ElevatedCard(Modifier.cardOutline()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.snapshot.name, style = MaterialTheme.typography.titleLarge)
                                    Text(trackingLabel(e.snapshot.tracking) + if (e.supersetGroup != null) " · " + e.supersetGroup else "", style = MaterialTheme.typography.bodySmall)
                                }
                                HelpIcon(stringResource(Res.string.help_logging_title), Res.string.help_logging)
                                IconButton({ }, Modifier.draggableHandle()) { Icon(painterResource(Res.drawable.ic_drag_indicator), stringResource(Res.string.training_reorder)) }
                                ActionIcon(Res.drawable.ic_more_vert, Res.string.action_more) { details = e.id }
                            }
                            val old = history.filter { it.id != id && it.status == SessionStatus.Finished &&
                                (settings[Flags.trainingPreviousGlobal] || it.templateId == session.templateId) }
                                .firstNotNullOfOrNull { prior -> prior.exercises.firstOrNull { it.exercise.id == e.exercise.id } }
                            val mode = e.snapshot.tracking
                            Column(Modifier.horizontalScroll(rememberScrollState())) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(Res.string.training_set), Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                                    Text(stringResource(Res.string.training_previous), Modifier.width(112.dp), style = MaterialTheme.typography.labelMedium)
                                    if (mode.hasWeight) Text(e.sets.firstOrNull()?.unit?.wire ?: settings[Flags.trainingUnits].wire, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                                    if (mode.hasReps) Text(repsLabel, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
                                    if (mode.hasDuration) Text(stringResource(Res.string.training_seconds), Modifier.width(84.dp), style = MaterialTheme.typography.labelMedium)
                                    if (mode.hasDistance) Text(stringResource(Res.string.training_metres), Modifier.width(84.dp), style = MaterialTheme.typography.labelMedium)
                                    Text("✓", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                                }
                                e.sets.forEachIndexed { setIndex, set -> key(set.id) {
                                    var advanced by remember { mutableStateOf(false) }
                                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { direction ->
                                        if (direction == SwipeToDismissBoxValue.EndToStart) {
                                            exerciseChange(e) { it.copy(sets = it.sets.filterNot { s -> s.id == set.id }) }; true
                                        } else false
                                    })
                                    SwipeToDismissBox(dismiss, backgroundContent = { Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxSize()) {} },
                                        enableDismissFromStartToEnd = false) {
                                        Surface(color = if (set.completed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TextButton({ advanced = true }, Modifier.width(48.dp).trainingDragHandle { delta -> exerciseChange(e) { block -> block.copy(sets = moveItem(block.sets, block.sets.indexOfFirst { it.id == set.id }, delta)) } }) { Text(if (set.kind == SetKind.Working) (setIndex + 1).toString() else setKindLabel(set.kind).take(1)) }
                                                val prior = old?.sets?.filter { it.kind == set.kind }?.getOrNull(e.sets.take(setIndex).count { it.kind == set.kind })
                                                Text(previousText(prior, mode), Modifier.width(112.dp).padding(4.dp), style = MaterialTheme.typography.bodySmall)
                                                fun input(field: String, value: Double?, label: String, integer: Boolean = false, step: Double = 1.0,
                                                    min: Double = 0.0, max: Double = Double.MAX_VALUE, transform: (PerformedSet, Double?) -> PerformedSet) {
                                                    keypad = KeypadEntry(label, value, step, integer, min, max) { entered ->
                                                        viewModel.edit(e.id!!, set.id!!, field) { s ->
                                                            val updated = transform(s, entered)
                                                            updated.copy(completed = s.completed && TrainingEngine.validateSet(updated, mode) == null)
                                                        }
                                                    }
                                                }
                                                if (mode.hasWeight) NumberCell(set.weight, 72) { input("weight", set.weight, e.snapshot.name + " · " + set.unit.wire, step = e.snapshot.increment) { s, v -> s.copy(weight = v) } }
                                                if (mode.hasReps) NumberCell(set.reps?.toDouble(), 64) { input("reps", set.reps?.toDouble(), e.snapshot.name + " · " + repsLabel, true, min = 1.0, max = 100000.0) { s, v -> s.copy(reps = v?.toInt()) } }
                                                if (mode.hasDuration) NumberCell(set.seconds, 84) { input("seconds", set.seconds, e.snapshot.name + " · s", min = 0.1) { s, v -> s.copy(seconds = v) } }
                                                if (mode.hasDistance) NumberCell(set.metres, 84) { input("metres", set.metres, e.snapshot.name + " · m", min = 0.1) { s, v -> s.copy(metres = v) } }
                                                val completeLabel = stringResource(Res.string.training_complete_set) + " " + (setIndex + 1) + ": " + e.snapshot.name
                                                Checkbox(set.completed, { viewModel.action {
                                                    training.complete(id, e.id!!, set.id!!)
                                                    if (!set.completed && settings[Flags.trainingHaptics]) bridge.haptic()
                                                } }, Modifier.width(48.dp).semantics { contentDescription = completeLabel })
                                            }
                                        }
                                    }
                                    if (advanced) AlertDialog(onDismissRequest = { advanced = false },
                                        title = { Text(e.snapshot.name + " · " + (setIndex + 1)) },
                                        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ChoiceRow(stringResource(Res.string.training_set), SetKind.entries, set.kind, { setKindLabel(it) }, help = Res.string.help_set_types) { value ->
                                                viewModel.action { training.editSet(id, e.id!!, set.id!!) { it.copy(kind = value) } }
                                            }
                                            ChoiceRow(stringResource(Res.string.training_unit), MassUnit.entries, set.unit, { it.wire }) { value ->
                                                viewModel.action { training.editSet(id, e.id!!, set.id!!) { it.copy(weight = it.weight?.let { weight -> TrainingEngine.mass(weight, it.unit, value) }, unit = value) } }
                                            }
                                            NumberEntry(Res.string.training_rpe, set.rpe, help = Res.string.help_effort) { value -> viewModel.edit(e.id!!, set.id!!, "rpe") { it.copy(rpe = value) } }
                                            NumberEntry(Res.string.training_rir, set.rir, allowZero = true, help = Res.string.help_effort) { value -> viewModel.edit(e.id!!, set.id!!, "rir") { it.copy(rir = value) } }
                                            CustomValuesFields(e.snapshot.fields, set.customValues) { values -> viewModel.edit(e.id!!, set.id!!, "custom") { it.copy(customValues = values) } }
                                            MoveControls(setIndex, e.sets.size) { delta -> exerciseChange(e) { it.copy(sets = moveItem(it.sets, setIndex, delta)) } }
                                            TextButton({ exerciseChange(e) { it.copy(sets = it.sets + set.copy(id = Ids.newId(), completed = false)) }; advanced = false }) { Text(stringResource(Res.string.action_duplicate)) }
                                            TextButton({ exerciseChange(e) { it.copy(sets = it.sets.filterNot { s -> s.id == set.id }) }; advanced = false }) { Text(stringResource(Res.string.action_delete)) }
                                            if (mode.hasWeight && settings[Flags.trainingPlateCalculator] && set.weight != null) TextButton({ plates = set.weight!! to set.unit; advanced = false }) { Text(stringResource(Res.string.training_plate_calculator)) }
                                        } }, confirmButton = { TextButton({ advanced = false }) { Text(stringResource(Res.string.action_done)) } })
                                } }
                            }
                            if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton({ exerciseChange(e) { block -> block.copy(sets = block.sets + (block.sets.lastOrNull() ?: PerformedSet(reps = if (mode.hasReps) 8 else null,
                                unit = settings[Flags.trainingUnits], weight = if (mode == TrackingMode.BodyweightReps) 0.0 else null)).copy(id = Ids.newId(), completed = false)) } }, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.training_add_set)) }
                        }
                    }
                }
            }
            item { Button({ pick = "" }, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.training_add_exercise)) } }
            if (session.status == SessionStatus.Finished) item { HistoryTimeEditor(session) { value -> change { it.copy(startedAt = value.startedAt, endedAt = value.endedAt, rating = value.rating) } } }
            item { NotesEntry(session.notes) { value -> change { it.copy(notes = value) } } }
            if (isActive) item { TextButton({ discard = true }) { Text(stringResource(Res.string.training_discard)) } }
            (viewModel.error ?: problem)?.let { message -> item { Text(stringResource(Res.string.status_error, message), color = MaterialTheme.colorScheme.error) } }
        }
    }
    keypad?.let { key -> NumericKeypad(key.label, key.value, key.step, key.integer, key.min, key.max, { keypad = null }, key.save) }
    pick?.let { block -> ExercisePicker(catalog, { pick = null }) { exercise ->
        viewModel.action { if (block.isEmpty()) training.addExercise(id, exercise.id!!) else training.replaceExercise(id, block, exercise.id!!) }
    } }
    session?.exercises?.firstOrNull { it.id == details }?.let { e ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(e.snapshot.name) }, text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesEntry(e.notes) { value -> exerciseChange(e) { it.copy(notes = value) } }
                TrainingTextField(e.supersetGroup.orEmpty(), { value -> exerciseChange(e) { it.copy(supersetGroup = value.ifBlank { null }) } }, Res.string.training_superset, help = Res.string.help_supersets)
                NumberEntry(Res.string.training_rest_seconds, e.restSeconds.toDouble(), allowBlank = false, integer = true, allowZero = true) { value ->
                    value?.let { rest -> exerciseChange(e) { it.copy(restSeconds = rest.toInt()) } }
                }
                MoveControls(session.exercises.indexOf(e), session.exercises.size) { delta -> change { it.copy(exercises = moveItem(it.exercises, it.exercises.indexOfFirst { block -> block.id == e.id }, delta)) } }
                TextButton({ pick = e.id; details = null }) { Text(stringResource(Res.string.training_replace_exercise)) }
                if (settings[Flags.trainingWarmups] && e.snapshot.tracking.hasWeight) TextButton({
                    val working = e.sets.firstOrNull { it.kind != SetKind.Warmup && it.weight != null }
                    if (working != null) exerciseChange(e) { block -> block.copy(sets =
                        TrainingEngine.warmups(working.weight!!, working.unit, e.snapshot.increment).map { target ->
                            PerformedSet(Ids.newId(), target, SetKind.Warmup, target.load?.amount, working.unit, target.reps)
                        } + block.sets) }
                    details = null
                }) { Text(stringResource(Res.string.training_add_warmups)) }
                TextButton({ change { it.copy(exercises = it.exercises.filterNot { block -> block.id == e.id }) }; details = null }) { Text(stringResource(Res.string.training_remove_exercise)) }
            }
        }, confirmButton = { TextButton({ details = null }) { Text(stringResource(Res.string.action_done)) } })
    }
    if (finish) AlertDialog(onDismissRequest = { finish = false }, title = { Text(stringResource(Res.string.training_finish_confirm)) },
        text = { Text(stringResource(Res.string.training_finish_body)) },
        confirmButton = { TextButton({ finish = false; scope.launch {
            if (viewModel.flushNow()) try { training.finish(id); onFinished(id) } catch (e: Exception) { problem = e.message }
        } }) { Text(stringResource(Res.string.training_finish)) } },
        dismissButton = { TextButton({ finish = false }) { Text(stringResource(Res.string.action_cancel)) } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(Res.string.training_discard)) },
        confirmButton = { TextButton({ discard = false; scope.launch {
            if (viewModel.flushNow()) try { training.discard(id); onBack() } catch (e: Exception) { problem = e.message }
        } }) { Text(stringResource(Res.string.action_delete)) } },
        dismissButton = { TextButton({ discard = false }) { Text(stringResource(Res.string.action_cancel)) } })
    plates?.let { weight -> PlateCalculator(weight.first, weight.second, settings, { plates = null }) }
}

@Composable private fun NumberCell(value: Double?, width: Int, onClick: () -> Unit) {
    OutlinedCard(onClick, Modifier.width(width.dp).padding(3.dp), border = cardBorder()) {
        Text(number(value).ifBlank { "—" }, Modifier.padding(horizontal = 8.dp, vertical = 12.dp).align(Alignment.CenterHorizontally), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable private fun PlateCalculator(weight: Double, unit: MassUnit, settings: SettingsSnapshot, dismiss: () -> Unit) {
    var target by remember { mutableStateOf<Double?>(weight) }
    val bar = TrainingEngine.mass(settings[Flags.trainingBar], MassUnit.Kg, unit)
    val available = settings[Flags.trainingPlates].split(',').mapNotNull { entry ->
        val p = entry.split(':'); val value = p.getOrNull(0)?.trim()?.toDoubleOrNull(); val count = p.getOrNull(1)?.trim()?.toIntOrNull()
        if (value != null && value > 0 && count != null && count >= 0) TrainingEngine.mass(value, MassUnit.Kg, unit) to count else null
    }.toMap()
    val result = target?.let { runCatching { TrainingEngine.plates(it, bar, available) }.getOrNull() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.training_plate_calculator) + " \u00b7 " + unit.wire) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberEntry(Res.string.training_target_weight, target, allowBlank = false) { target = it }
            Text(stringResource(Res.string.training_bar) + ": " + number(bar))
            result?.let {
                Text(stringResource(Res.string.training_plates_result, it.platesPerSide.joinToString(" + ") { p -> number(p) }))
                if (it.remainder > 0.001) Text(stringResource(Res.string.training_plate_remainder, number(it.remainder)))
            }
        } }, confirmButton = { TextButton(dismiss) { Text(stringResource(Res.string.action_done)) } })
}

@Composable private fun HistoryTimeEditor(session: WorkoutSessionDocument, change: (WorkoutSessionDocument) -> Unit) {
    var start by remember(session.id) { mutableStateOf(session.startedAt.toString()) }
    var end by remember(session.id) { mutableStateOf(session.endedAt.toString()) }
    val a = runCatching { Instant.parse(start) }.getOrNull()
    val b = runCatching { Instant.parse(end) }.getOrNull()
    val valid = a != null && b != null && b >= a
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(start, { value -> start = value; val at = runCatching { Instant.parse(value) }.getOrNull()
            if (at != null && b != null && b >= at) change(session.copy(startedAt = at, endedAt = b))
        }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_started_at)) }, isError = !valid,
            supportingText = { Text(stringResource(Res.string.training_time_hint)) })
        OutlinedTextField(end, { value -> end = value; val at = runCatching { Instant.parse(value) }.getOrNull()
            if (a != null && at != null && at >= a) change(session.copy(startedAt = a, endedAt = at))
        }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.training_ended_at)) }, isError = !valid)
        ChoiceRow(stringResource(Res.string.training_rating), (0..5).toList(), session.rating ?: 0, { it.toString() }) { change(session.copy(rating = it)) }
    }
}
