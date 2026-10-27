package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import io.github.vargfor.ftmnotes.designsystem.components.EmptyState
import io.github.vargfor.ftmnotes.designsystem.components.HelpIcon
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.food.engine.QuantityFormatter
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import kotlin.time.Clock

@Composable fun contentTypeLabel(type: DocumentType): String = stringResource(when(type) {
    DocumentType.Note -> Res.string.type_note; DocumentType.Recipe -> Res.string.type_recipe
    DocumentType.ShoppingList -> Res.string.training_prompt_shopping; DocumentType.Exercise -> Res.string.training_prompt_exercise
    DocumentType.WorkoutTemplate -> Res.string.training_prompt_template; DocumentType.WorkoutSession -> Res.string.training_prompt_session
    DocumentType.TrainingPlan -> Res.string.training_prompt_plan; DocumentType.Settings -> Res.string.action_settings
})
@Composable internal fun trackingLabel(mode: TrackingMode) = stringResource(when(mode) {
    TrackingMode.WeightReps -> Res.string.training_weight_reps; TrackingMode.BodyweightReps -> Res.string.training_bodyweight_reps
    TrackingMode.Reps -> Res.string.training_reps; TrackingMode.Duration -> Res.string.training_duration
    TrackingMode.Distance -> Res.string.training_distance; TrackingMode.DurationDistance -> Res.string.training_duration_distance
})
@Composable internal fun setKindLabel(kind: SetKind) = stringResource(when(kind) {
    SetKind.Working -> Res.string.training_working; SetKind.Warmup -> Res.string.training_warmup
    SetKind.Drop -> Res.string.training_drop; SetKind.Failure -> Res.string.training_failure; SetKind.Amrap -> Res.string.training_amrap
})
@Composable internal fun loadKindLabel(kind: LoadKind) = stringResource(when(kind) {
    LoadKind.Absolute -> Res.string.training_absolute; LoadKind.PercentOneRm -> Res.string.training_percent_1rm
    LoadKind.PercentTrainingMax -> Res.string.training_percent_tm; LoadKind.Rpe -> Res.string.training_rpe; LoadKind.Rir -> Res.string.training_rir
})
@Composable internal fun progressionLabel(kind: ProgressionKind) = stringResource(when(kind) {
    ProgressionKind.None -> Res.string.training_none; ProgressionKind.Linear -> Res.string.training_linear
    ProgressionKind.Double -> Res.string.training_double; ProgressionKind.Manual -> Res.string.training_manual
})
internal fun number(value: Double?): String = value?.let { QuantityFormatter.decimal(it) }.orEmpty()
internal fun duration(seconds: Long): String = (seconds.coerceAtLeast(0) / 60).toString() + ":" + (seconds.coerceAtLeast(0) % 60).toString().padStart(2, '0')
internal fun previousText(set: PerformedSet?, mode: TrackingMode): String = if (set == null) "—" else buildList {
    if (mode.hasWeight) add(number(set.weight) + " " + set.unit.wire)
    if (mode.hasReps) add((set.reps ?: 0).toString())
    if (mode.hasDuration) add(duration((set.seconds ?: 0.0).toLong()))
    if (mode.hasDistance) add(number(set.metres) + " m")
}.joinToString(" × ")

@Composable fun ActiveWorkoutBar(active: ActiveTraining?, onOpen: (String) -> Unit) {
    if (active == null) return
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(active.state.restToken) { while (true) { now = Clock.System.now().toEpochMilliseconds(); delay(1000) } }
    val bridge = koinInject<AppPlatform>().bridge
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().clickable { onOpen(active.state.sessionId) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(active.session.title.ifBlank { stringResource(Res.string.training_resume) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (active.state.restEnd != null) {
                    val remaining = TrainingService.remainingMillis(active.state, now, bridge.elapsedRealtimeMillis(), bridge.bootId())
                    Text(if (remaining <= 0) stringResource(Res.string.training_timer_finished) else stringResource(Res.string.training_timer_remaining, duration((remaining + 999) / 1000)))
                }
            }
            Text(stringResource(Res.string.training_resume), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable internal fun ExercisePicker(catalog: List<ExerciseDocument>, dismiss: () -> Unit, choose: (ExerciseDocument) -> Unit) {
    var search by remember { mutableStateOf(ExerciseSearch()) }
    val shown = remember(catalog, search) { search.apply(catalog) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.training_add_exercise)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                item { ExerciseBrowseControls(catalog, search) { search = it } }
                item { Text(stringResource(Res.string.exercise_count, shown.size), style = MaterialTheme.typography.labelLarge) }
                if (shown.isEmpty()) item { Text(stringResource(Res.string.exercise_no_results)) }
                items(shown, key = { it.id ?: it.title }) { e ->
                    ListItem(headlineContent = { Text(e.title) }, supportingContent = { Text(exerciseSummary(e)) },
                        modifier = Modifier.fillMaxWidth().clickable { choose(e); dismiss() },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text(stringResource(Res.string.action_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NumericKeypad(
    label: String, value: Double?, increment: Double = 1.0, integer: Boolean = false,
    min: Double = 0.0, max: Double = Double.MAX_VALUE, dismiss: () -> Unit, save: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(number(value)) }
    var replace by remember { mutableStateOf(true) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = text.isBlank() || parsed != null && parsed.isFinite() && parsed in min..max && (!integer || parsed % 1 == 0.0)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(text, {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = !valid)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ text = number(((parsed ?: 0.0) - increment).coerceAtLeast(min)); replace = true }, Modifier.weight(1f)) { Text("−" + number(increment)) }
                OutlinedButton({ text = number(((parsed ?: 0.0) + increment).coerceAtMost(max)); replace = true }, Modifier.weight(1f)) { Text("+" + number(increment)) }
            }
            listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf(if (integer) "" else ".", "0", "⌫")).forEach { keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { keys.forEach { key ->
                    FilledTonalButton({
                        text = when { key == "⌫" -> text.dropLast(1); replace -> if (key == ".") "0." else key
                            key == "." && "." in text -> text; else -> text + key }; replace = false
                    }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = key.isNotEmpty()) { Text(key, style = MaterialTheme.typography.titleLarge) }
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton({ save(null); dismiss() }, Modifier.weight(1f)) { Text(stringResource(Res.string.training_clear)) }
                Button({ save(parsed); dismiss() }, Modifier.weight(1f), enabled = valid && text.isNotBlank()) { Text(stringResource(Res.string.action_done)) }
            }
        }
    }
}

/** Long press a set number to move vertically; explicit move buttons remain available in its menu. */
@Composable internal fun Modifier.trainingDragHandle(onMove: (Int) -> Unit): Modifier {
    val move by rememberUpdatedState(onMove)
    var height by remember { mutableFloatStateOf(48f) }
    return onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }.pointerInput(Unit) {
        var dragged = 0f
        detectDragGesturesAfterLongPress(onDragStart = { dragged = 0f }, onDragEnd = { dragged = 0f }, onDragCancel = { dragged = 0f }) { change, amount ->
            change.consume(); dragged += amount.y
            if (abs(dragged) >= height) { move(if (dragged > 0) 1 else -1); dragged = 0f }
        }
    }
}

/** Retain the typing buffer while delayed database echoes arrive. */
@Composable internal fun TrainingTextField(value: String, change: (String) -> Unit, label: StringResource,
    modifier: Modifier = Modifier, minLines: Int = 1, help: StringResource? = null) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    OutlinedTextField(text, { text = it; change(it) }, modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        label = { Text(stringResource(label)) }, minLines = minLines,
        trailingIcon = help?.let { body -> { HelpIcon(stringResource(label), body) } })
}

@Composable fun TrainingSelectionPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(painterResource(Res.drawable.ic_fitness_center), stringResource(Res.string.training_empty_collection), stringResource(Res.string.training_select_hint))
    }
}
