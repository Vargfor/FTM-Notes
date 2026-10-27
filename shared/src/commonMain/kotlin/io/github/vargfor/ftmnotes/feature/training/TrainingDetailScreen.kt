package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.feature.settings.ChoiceRow
import io.github.vargfor.ftmnotes.feature.food.NumberEntry
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@Composable
fun TrainingDetailScreen(id: String, onBack: () -> Unit, onEdit: (String, String) -> Unit,
    onWorkout: (String) -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val repository: DocumentRepository = koinInject()
    val training: TrainingService = koinInject()
    val settings: SettingsRepository = koinInject()
    val snapshot by settings.snapshot.collectAsStateWithLifecycle()
    val bridge = koinInject<AppPlatform>().bridge
    val imports: ImportCoordinator = koinInject()
    val stored by remember(id) { repository.observe(id) }.collectAsStateWithLifecycle(null)
    val doc = stored?.document
    val cursors by training.cursors.collectAsStateWithLifecycle(emptyMap())
    val history by training.sessions.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var suggestions by remember(id) { mutableStateOf<List<ProgressionSuggestion>>(emptyList()) }
    var acceptedSets by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    var accepted by remember(id) { mutableStateOf<Set<String?>>(emptySet()) }
    var next by remember(id) { mutableStateOf<NextWorkout?>(null) }
    var cursorEditor by remember { mutableStateOf(false) }
    var cursorWeek by remember { mutableIntStateOf(1) }
    var cursorDay by remember { mutableIntStateOf(0) }
    val uri = LocalUriHandler.current
    val undoLabel = stringResource(Res.string.action_undo)
    val deletedLabel = stringResource(Res.string.training_moved_trash)
    val getWeightLabel = stringResource(Res.string.training_weight)
    val getVolumeLabel = stringResource(Res.string.training_volume_label)
    val savedLabel = stringResource(Res.string.training_progress_saved)
    fun run(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: Exception) { problem = e.message } } }
    LaunchedEffect(doc, history, snapshot, cursors) {
        if (doc is TrainingPlanDocument) next = training.nextWorkout(id)
        if (doc is WorkoutSessionDocument && doc.status == SessionStatus.Finished) suggestions = training.suggestions(id)
    }
    Scaffold(topBar = { BackTopBar(doc?.title.orEmpty(), onBack) {
        if (doc != null) ActionIcon(Res.drawable.ic_edit, Res.string.ic_edit_label) {
            if (doc is WorkoutSessionDocument) onWorkout(id) else onEdit(id, doc.documentType.wire)
        }
        Box {
            ActionIcon(Res.drawable.ic_more_vert, Res.string.action_more) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(Res.string.training_settings)) }, { menu = false; onSettings() })
                if (doc != null) {
                    DropdownMenuItem({ Text(stringResource(Res.string.training_export)) }, { menu = false; run {
                        val file = imports.exportSelection(setOf(id)); bridge.exportFile(file, doc.documentType.wire + ".ftm.json", "application/json")
                    } })
                    DropdownMenuItem({ Text(stringResource(if (doc.pinned) Res.string.action_unpin else Res.string.action_pin)) }, { menu = false; run { repository.update(id) { it.withOrganisation(pinned = !it.pinned) } } })
                    DropdownMenuItem({ Text(stringResource(if (doc.archived) Res.string.action_unarchive else Res.string.action_archive)) }, { menu = false; run { repository.update(id) { it.withOrganisation(archived = !it.archived) } } })
                    if (doc !is WorkoutSessionDocument) DropdownMenuItem({ Text(stringResource(Res.string.action_duplicate)) }, { menu = false; run { onOpen(repository.save(doc.withMeta(Ids.newId(), null, null).withOrganisation(pinned = false, archived = false))) } })
                    DropdownMenuItem({ Text(stringResource(Res.string.action_delete)) }, { menu = false; run {
                        repository.moveToTrash(id)
                        if (snackbar.showSnackbar(deletedLabel, undoLabel) == SnackbarResult.ActionPerformed) repository.restore(id) else onBack()
                    } })
                }
            }
        }
    } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (doc == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else {
                item {
                    Text(doc.title, style = MaterialTheme.typography.headlineMedium)
                    if (doc.tags.isNotEmpty()) Text(doc.tags.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
                }
                when (doc) {
                    is ExerciseDocument -> {
                        item { Text(exerciseSummary(doc)) }
                        item { Text(trackingLabel(doc.tracking), style = MaterialTheme.typography.bodyMedium) }
                        if (doc.notes.isNotBlank() && doc.instructionUrl?.contains("github.com/yuhonas/free-exercise-db/") == true)
                            item { Text(stringResource(Res.string.exercise_instructions), style = MaterialTheme.typography.titleLarge) }
                        if (doc.notes.isNotBlank()) item { Text(doc.notes) }
                        doc.instructionUrl?.let { link -> item { TextButton({ runCatching { uri.openUri(link) }.onFailure { problem = it.message } }) { Text(stringResource(if (link.contains("github.com/yuhonas/free-exercise-db/")) Res.string.exercise_source else Res.string.training_instruction_url)) } } }
                        item { Text(stringResource(Res.string.training_history), style = MaterialTheme.typography.titleLarge) }
                        val matching = history.filter { it.status == SessionStatus.Finished && it.exercises.any { e -> e.exercise.id == id } }
                        if (matching.isEmpty()) item { Text(stringResource(Res.string.training_no_history)) }
                        else item { ExerciseProgress(matching, id, snapshot) }
                        items(matching, key = { it.id!! }) { session ->
                            OutlinedCard({ onOpen(session.id!!) }, Modifier.fillMaxWidth(), border = cardBorder()) { Column(Modifier.padding(16.dp)) {
                                Text(session.title, style = MaterialTheme.typography.titleMedium)
                                Text(session.startedAt?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
                                session.exercises.filter { it.exercise.id == id }.flatMap { it.sets }.filter { it.completed }.forEach { set ->
                                    Text(setKindLabel(set.kind) + " · " + previousText(set, doc.tracking))
                                }
                            } }
                        }
                    }
                    is WorkoutTemplateDocument -> {
                        item { Button({ run { onWorkout(training.start(id)) } }, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.training_start)) } }
                        if (doc.notes.isNotBlank()) item { Text(doc.notes) }
                        items(doc.blocks, key = { it.id!! }) { block ->
                            OutlinedCard({ block.exercise.id?.let(onOpen) }, Modifier.fillMaxWidth(), border = cardBorder()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(block.exercise.name, style = MaterialTheme.typography.titleLarge)
                                block.supersetGroup?.let { Text(stringResource(Res.string.training_superset) + ": " + it) }
                                Text(stringResource(Res.string.training_rest_seconds) + ": " + (block.restSeconds ?: 90))
                                block.sets.forEachIndexed { index, target -> Text((index + 1).toString() + " · " + setKindLabel(target.kind) + " · " + targetText(target)) }
                                if (block.notes.isNotBlank()) Text(block.notes)
                            } }
                        }
                    }
                    is TrainingPlanDocument -> {
                        item {
                            val n = next
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(Res.string.training_weeks) + ": " + doc.weeks + " · " + progressionLabel(doc.progression.kind))
                                if (n?.day != null) {
                                    Text(stringResource(Res.string.training_week_day, n.cursor.week, n.day!!.name))
                                    Button({ run {
                                        settings.set(Flags.activePlan, id)
                                        onWorkout(training.startNext(id))
                                    } }, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.training_start_plan)) }
                                } else if (n != null) Text(stringResource(Res.string.training_finished_plan))
                                if (snapshot[Flags.activePlan] != id) OutlinedButton({ run { settings.set(Flags.activePlan, id) } }) { Text(stringResource(Res.string.training_follow)) }
                                OutlinedButton({ cursorWeek = (n?.cursor?.week ?: 1).coerceAtMost(doc.weeks); cursorDay = n?.cursor?.day ?: 0; cursorEditor = true }, enabled = doc.days.isNotEmpty()) { Text(stringResource(Res.string.training_choose_day)) }
                                if (doc.notes.isNotBlank()) Text(doc.notes)
                            }
                        }
                        items(doc.days, key = { it.id!! }) { day ->
                            OutlinedCard({ day.template.id?.let(onOpen) }, Modifier.fillMaxWidth(), border = cardBorder()) { Column(Modifier.padding(16.dp)) {
                                Text(day.name, style = MaterialTheme.typography.titleLarge); Text(day.template.name)
                            } }
                        }
                        if (doc.overrides.isNotEmpty()) item { Text(stringResource(Res.string.training_overrides), style = MaterialTheme.typography.titleLarge) }
                        items(doc.overrides) { override -> Text(stringResource(Res.string.training_week_day, override.week, override.exercise.name) + ": " + override.sets.joinToString(" / ") { targetText(it) }) }
                    }
                    is WorkoutSessionDocument -> {
                        if (doc.status != SessionStatus.Finished) item {
                            Button({ run { onWorkout(training.resume(id)) } }) { Text(stringResource(Res.string.training_resume)) }
                        } else {
                            val totals = TrainingEngine.totals(doc, snapshot[Flags.trainingIncludeWarmups])
                            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(Res.string.training_summary), style = MaterialTheme.typography.titleLarge)
                                Text(doc.startedAt?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(Res.string.training_total_sets, totals.sets))
                                Text(stringResource(Res.string.training_elapsed, duration(totals.seconds)))
                                Text(stringResource(Res.string.training_volume, number(TrainingEngine.mass(totals.volumeKg, MassUnit.Kg, snapshot[Flags.trainingUnits])), snapshot[Flags.trainingUnits].wire))
                                if (doc.notes.isNotBlank()) Text(doc.notes)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({ onWorkout(id) }) { Text(stringResource(Res.string.training_edit_history)) }
                                    OutlinedButton({ run { onOpen(training.saveAsTemplate(id)) } }) { Text(stringResource(Res.string.training_save_routine)) }
                                    if (doc.templateId != null) OutlinedButton({ run { training.updateTemplate(id); snackbar.showSnackbar(savedLabel) } }) { Text(stringResource(Res.string.training_update_routine)) }
                                    if (doc.planId != null && !snapshot[Flags.trainingAutoAdvance]) OutlinedButton({ run { training.advanceFrom(doc); snackbar.showSnackbar(savedLabel) } }) { Text(stringResource(Res.string.training_advance)) }
                                }
                            } }
                            if (snapshot[Flags.trainingPrs]) {
                                val prs = TrainingEngine.strengthPrs(doc, history.filter { it.endedAt != null && it.endedAt!! < doc.endedAt!! },
                                    if (snapshot[Flags.trainingBrzycki]) OneRmFormula.Brzycki else OneRmFormula.Epley)
                                item {
                                    HelpLabel(stringResource(Res.string.training_prs), Res.string.help_prs, style = MaterialTheme.typography.titleLarge)
                                    if (prs.isEmpty()) Text(stringResource(Res.string.training_no_prs))
                                    prs.forEach { (exercise, types) ->
                                        Text(doc.exercises.first { it.exercise.id == exercise || it.exercise.name == exercise }.snapshot.name + " · " +
                                            types.joinToString(", ") { when(it) { "weight" -> getWeightLabel; "volume" -> getVolumeLabel; else -> "e1RM" } })
                                    }
                                }
                            }
                            if (suggestions.isNotEmpty()) {
                                item {
                                    HelpLabel(stringResource(Res.string.training_suggestions), Res.string.help_suggestions, style = MaterialTheme.typography.titleLarge)
                                    Button({ run { training.acceptSuggestions(id, suggestions.mapNotNull { it.exerciseId }.toSet()); accepted = suggestions.map { it.exerciseId }.toSet() } },
                                        enabled = suggestions.any { it.exerciseId !in accepted }) { Text(stringResource(Res.string.training_accept_all)) }
                                }
                                items(suggestions) { suggestion ->
                                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(doc.exercises.firstOrNull { it.exercise.id == suggestion.exerciseId }?.snapshot?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(when(suggestion.reason) {
                                            "increase" -> Res.string.training_suggest_increase; "deload" -> Res.string.training_suggest_deload
                                            "reps" -> Res.string.training_suggest_reps; "manual" -> Res.string.training_suggest_manual; else -> Res.string.training_suggest_repeat
                                        }))
                                        suggestion.targets.forEachIndexed { index, target ->
                                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                Text((index + 1).toString() + ": " + targetText(target), Modifier.weight(1f))
                                                TextButton({ run {
                                                    training.acceptSuggestions(id, setOfNotNull(suggestion.exerciseId), setOfNotNull(target.id))
                                                    target.id?.let { acceptedSets = acceptedSets + it }
                                                } }, enabled = target.id != null && target.id !in acceptedSets && suggestion.exerciseId !in accepted) {
                                                    Text(stringResource(if (target.id in acceptedSets || suggestion.exerciseId in accepted) Res.string.training_progress_saved else Res.string.training_accept))
                                                }
                                            }
                                        }
                                        TextButton({ run { training.acceptSuggestions(id, setOfNotNull(suggestion.exerciseId)); accepted = accepted + suggestion.exerciseId } },
                                            enabled = suggestion.exerciseId !in accepted) { Text(stringResource(if (suggestion.exerciseId in accepted) Res.string.training_progress_saved else Res.string.training_accept)) }
                                    } }
                                }
                            }
                        }
                        items(doc.exercises, key = { it.id!! }) { exercise ->
                            OutlinedCard({ exercise.exercise.id?.let(onOpen) }, Modifier.fillMaxWidth(), border = cardBorder()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(exercise.snapshot.name, style = MaterialTheme.typography.titleLarge)
                                exercise.sets.filter { it.completed }.forEach { set -> Text(setKindLabel(set.kind) + " · " + previousText(set, exercise.snapshot.tracking)) }
                                if (exercise.notes.isNotBlank()) Text(exercise.notes)
                            } }
                        }
                    }
                    else -> Unit
                }
            }
            problem?.let { item { Text(stringResource(Res.string.status_error, it), color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (cursorEditor && doc is TrainingPlanDocument) AlertDialog(onDismissRequest = { cursorEditor = false }, title = { Text(stringResource(Res.string.training_choose_day)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberEntry(Res.string.training_plan_week, cursorWeek.toDouble(), allowBlank = false, integer = true) { value -> value?.let { cursorWeek = it.toInt() } }
            ChoiceRow(stringResource(Res.string.training_days), doc.days.indices.toList(), cursorDay, { doc.days[it].name }, help = Res.string.help_plan_days) { cursorDay = it }
        } }, confirmButton = { TextButton({ run { training.setCursor(id, PlanCursor(cursorWeek, cursorDay)); cursorEditor = false } }, enabled = cursorWeek in 1..doc.weeks && cursorDay in doc.days.indices) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton({ cursorEditor = false }) { Text(stringResource(Res.string.action_cancel)) } })

}

internal fun targetText(target: SetTarget): String = buildList {
    target.reps?.let { add(it.toString()) }
    if (target.repMin != null || target.repMax != null) add((target.repMin?.toString() ?: "") + "–" + (target.repMax?.toString() ?: ""))
    target.load?.let { add(number(it.amount) + if (it.kind == LoadKind.Absolute) " " + it.unit.wire else " " + when(it.kind) {
        LoadKind.PercentOneRm -> "% 1RM"; LoadKind.PercentTrainingMax -> "% TM"; LoadKind.Rpe -> "RPE"; LoadKind.Rir -> "RIR"; else -> ""
    }) }
    target.seconds?.let { add(duration(it.toLong())) }; target.metres?.let { add(number(it) + " m") }
}.joinToString(" × ")

@Composable private fun ExerciseProgress(sessions: List<WorkoutSessionDocument>, exercise: String, settings: SettingsSnapshot) {
    var metric by remember { mutableStateOf("e1RM") }
    val weightLabel = stringResource(Res.string.training_weight)
    val volumeLabel = stringResource(Res.string.training_volume_label)
    val points = sessions.reversed().map { session ->
        val sets = session.exercises.filter { it.exercise.id == exercise }.flatMap { it.sets }.filter { it.completed && it.kind != SetKind.Warmup }
        val value = when(metric) {
            weightLabel -> sets.maxOfOrNull { TrainingEngine.kg(it.weight ?: 0.0, it.unit) } ?: 0.0
            volumeLabel -> sets.sumOf { TrainingEngine.kg(it.weight ?: 0.0, it.unit) * (it.reps ?: 0) }
            else -> sets.maxOfOrNull { TrainingEngine.e1rm(TrainingEngine.kg(it.weight ?: 0.0, it.unit), it.reps ?: 0,
                if (settings[Flags.trainingBrzycki]) OneRmFormula.Brzycki else OneRmFormula.Epley) ?: 0.0 } ?: 0.0
        }
        TrainingEngine.mass(value, MassUnit.Kg, settings[Flags.trainingUnits])
    }
    val color = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceRow(stringResource(Res.string.training_progress), listOf("e1RM", weightLabel, volumeLabel), metric, { it }, help = Res.string.help_e1rm) { metric = it }
        if (points.any { it > 0 }) {
            Text(metric + ": " + number(points.last()) + " " + settings[Flags.trainingUnits].wire)
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val max = points.maxOrNull()!!.coerceAtLeast(1.0)
                val path = Path()
                points.forEachIndexed { index, value ->
                    val x = if (points.size == 1) size.width / 2 else index * size.width / (points.size - 1)
                    val y = (size.height - 12.dp.toPx()) * (1 - value / max).toFloat() + 6.dp.toPx()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(color, 3.dp.toPx(), Offset(x, y))
                }
                drawPath(path, color, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
