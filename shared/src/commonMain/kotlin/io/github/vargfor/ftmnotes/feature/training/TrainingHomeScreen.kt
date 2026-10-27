package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.core.training.ExerciseSearch
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@Composable
fun TrainingHomeScreen(
    onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onOpen: (String) -> Unit = {},
    onWorkout: (String) -> Unit = {}, onNew: (DocumentType) -> Unit = {}, onImport: () -> Unit = {},
    onArchive: (String) -> Unit = {}, onTrash: (String) -> Unit = {}, initialArea: DocumentType = DocumentType.WorkoutTemplate,
) {
    val training: TrainingService = koinInject()
    val settings: SettingsRepository = koinInject()
    val snapshot by settings.snapshot.collectAsStateWithLifecycle()
    val active by training.active.collectAsStateWithLifecycle()
    val routines by training.templates.collectAsStateWithLifecycle(emptyList())
    val plans by training.plans.collectAsStateWithLifecycle(emptyList())
    val history by training.sessions.collectAsStateWithLifecycle(emptyList())
    val catalog by training.exercises.collectAsStateWithLifecycle(emptyList())
    val cursors by training.cursors.collectAsStateWithLifecycle(emptyMap())
    val startupError by training.error.collectAsStateWithLifecycle()
    var area by remember { mutableStateOf(initialArea) }
    var search by remember { mutableStateOf(ExerciseSearch()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var next by remember { mutableStateOf<NextWorkout?>(null) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val emptyName = stringResource(Res.string.training_workout_title)
    fun launch(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { problem = e.message } } }
    LaunchedEffect(snapshot, history, plans, cursors) {
        next = snapshot[Flags.activePlan].takeIf { it.isNotBlank() }?.let { training.nextWorkout(it) }
    }
    Scaffold(topBar = {
        SectionTopBar(stringResource(Res.string.tab_training), stringResource(Res.string.training_subtitle), onOpenSearch, onOpenSettings,
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(), extraActions = {
                Box {
                    ActionIcon(Res.drawable.ic_more_vert, Res.string.action_more) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(Res.string.action_import)) }, { menu = false; onImport() })
                        DropdownMenuItem({ Text(stringResource(Res.string.notes_archive)) }, { menu = false; onArchive(area.wire) })
                        DropdownMenuItem({ Text(stringResource(Res.string.notes_trash)) }, { menu = false; onTrash(area.wire) })
                    }
                }
            })
    }, floatingActionButton = {
        if (area != DocumentType.WorkoutSession) {
            val label = stringResource(when(area) { DocumentType.Exercise -> Res.string.training_new_exercise; DocumentType.TrainingPlan -> Res.string.training_new_plan; else -> Res.string.training_new_routine })
            ExtendedFloatingActionButton(onClick = { onNew(area) }, icon = { Icon(painterResource(Res.drawable.ic_add), label) }, text = { Text(label) })
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 108.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ launch { onWorkout(training.start(title = emptyName)) } }) { Text(stringResource(if (active == null) Res.string.training_empty_workout else Res.string.training_resume)) }
                OutlinedButton(onImport) { Text(stringResource(Res.string.training_import)) }
            } }
            next?.let { n -> item {
                ElevatedCard(Modifier.fillMaxWidth().cardOutline()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(n.plan.title, style = MaterialTheme.typography.titleMedium)
                        Text(n.day?.let { stringResource(Res.string.training_week_day, n.cursor.week, it.name) } ?: stringResource(Res.string.training_finished_plan))
                        if (n.day != null) Button({ launch { onWorkout(training.startNext(n.plan.id!!)) } }) { Text(stringResource(Res.string.training_next_workout)) }
                    }
                }
            } }
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DocumentType.WorkoutTemplate to Res.string.training_routines, DocumentType.TrainingPlan to Res.string.training_plans,
                    DocumentType.WorkoutSession to Res.string.training_history, DocumentType.Exercise to Res.string.training_exercises).forEach { (type, label) ->
                    FilterChip(area == type, { area = type; search = ExerciseSearch() }, label = { Text(stringResource(label)) })
                }
            } }
            if (area == DocumentType.Exercise) {
                item { ExerciseBrowseControls(catalog, search) { search = it } }
            }
            val shown: List<FtmDocument> = when(area) {
                DocumentType.Exercise -> search.apply(catalog)
                DocumentType.TrainingPlan -> plans.filterNot { it.archived }
                DocumentType.WorkoutSession -> history.filterNot { it.archived }
                else -> routines.filterNot { it.archived }
            }
            if (area == DocumentType.Exercise) item { Text(stringResource(Res.string.exercise_count, shown.size), style = MaterialTheme.typography.labelLarge) }
            if (shown.isEmpty()) item {
                EmptyState(painterResource(Res.drawable.ic_fitness_center), stringResource(if (area == DocumentType.Exercise) Res.string.training_empty_library else Res.string.training_empty_collection), stringResource(if (area == DocumentType.Exercise) Res.string.exercise_no_results else Res.string.training_empty_body))
            }
            items(shown, key = { it.id!! }) { doc ->
                ElevatedCard(onClick = { onOpen(doc.id!!) }, modifier = Modifier.fillMaxWidth().cardOutline()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(doc.title.ifBlank { stringResource(Res.string.note_untitled) }, style = MaterialTheme.typography.titleMedium)
                        when(doc) {
                            is ExerciseDocument -> Text(exerciseSummary(doc), style = MaterialTheme.typography.bodyMedium)
                            is WorkoutTemplateDocument -> {
                                Text(doc.blocks.joinToString(" · ") { it.exercise.name }, style = MaterialTheme.typography.bodyMedium)
                                Button({ launch { onWorkout(training.start(doc.id)) } }) { Text(stringResource(Res.string.training_start)) }
                            }
                            is TrainingPlanDocument -> {
                                Text(doc.days.joinToString(" · ") { it.name }, style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton({ launch { settings.set(Flags.activePlan, doc.id!!); next = training.nextWorkout(doc.id!!) } }) { Text(stringResource(Res.string.training_start_plan)) }
                            }
                            is WorkoutSessionDocument -> {
                                Text(doc.startedAt?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
                                Text(if (doc.status == SessionStatus.Draft) stringResource(Res.string.training_draft) else stringResource(Res.string.training_total_sets, io.github.vargfor.ftmnotes.core.training.TrainingEngine.totals(doc, snapshot[Flags.trainingIncludeWarmups]).sets))
                            }
                            else -> Unit
                        }
                        if (doc.tags.isNotEmpty()) Text(doc.tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            (problem ?: startupError)?.let { item { Text(stringResource(Res.string.status_error, it), color = MaterialTheme.colorScheme.error) } }
        }
    }
}
