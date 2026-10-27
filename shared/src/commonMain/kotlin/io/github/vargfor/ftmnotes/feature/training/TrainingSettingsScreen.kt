package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.core.training.TrainingEngine
import io.github.vargfor.ftmnotes.data.TrainingService
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.feature.food.NumberEntry
import io.github.vargfor.ftmnotes.feature.settings.*
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@Composable fun TrainingSettingsScreen(onBack: () -> Unit) {
    val repository: SettingsRepository = koinInject()
    val settings by repository.snapshot.collectAsStateWithLifecycle()
    val catalog by koinInject<TrainingService>().exercises.collectAsStateWithLifecycle(emptyList())
    val bridge = koinInject<AppPlatform>().bridge
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var maxEditor by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<ExerciseDocument?>(null) }
    var maximum by remember { mutableStateOf<Double?>(null) }
    var tm by remember { mutableStateOf<Double?>(null) }
    fun <T> set(flag: FeatureFlag<T>, value: T) { scope.launch { try { repository.set(flag, value) } catch (e: Exception) { problem = e.message } } }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.training_settings), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ChoiceRow(stringResource(Res.string.training_unit), MassUnit.entries, settings[Flags.trainingUnits], { it.wire }) { set(Flags.trainingUnits, it) } }
            item { ChoiceRow(stringResource(Res.string.training_rest_seconds), Flags.trainingRestSeconds.options, settings[Flags.trainingRestSeconds], { it.toString() }) { set(Flags.trainingRestSeconds, it) } }
            listOf(Flags.trainingPrefill to Res.string.training_prefill, Flags.trainingPreviousGlobal to Res.string.training_previous_global,
                Flags.trainingRest to Res.string.training_auto_rest, Flags.trainingKeepAwake to Res.string.training_keep_awake,
                Flags.trainingHaptics to Res.string.training_haptics, Flags.trainingSupersets to Res.string.training_auto_superset,
                Flags.trainingSuggestions to Res.string.training_suggestions, Flags.trainingAutoAdvance to Res.string.training_auto_advance,
                Flags.trainingWarmups to Res.string.training_warmup_setting, Flags.trainingPlateCalculator to Res.string.training_plate_setting,
                Flags.trainingPrs to Res.string.training_pr_setting, Flags.trainingIncludeWarmups to Res.string.training_warmup_stats,
                Flags.trainingBrzycki to Res.string.training_formula, Flags.trainingBodyweight to Res.string.training_bodyweight_setting).forEach { (flag, label) ->
                item { SwitchRow(stringResource(label), settings[flag], { set(flag, it) }, help = when(flag) {
                    Flags.trainingPrefill -> Res.string.help_prefill
                    Flags.trainingPreviousGlobal -> Res.string.help_previous_global
                    Flags.trainingSupersets -> Res.string.help_supersets
                    Flags.trainingSuggestions -> Res.string.help_suggestions
                    Flags.trainingAutoAdvance -> Res.string.help_auto_advance
                    Flags.trainingWarmups -> Res.string.help_warmups
                    Flags.trainingPlateCalculator -> Res.string.help_plates
                    Flags.trainingPrs -> Res.string.help_prs
                    Flags.trainingIncludeWarmups -> Res.string.help_volume
                    Flags.trainingBrzycki -> Res.string.help_e1rm
                    else -> null
                }) }
            }
            item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HelpLabel(stringResource(Res.string.training_plate_calculator), Res.string.help_plates, style = MaterialTheme.typography.titleLarge)
                Text(settings[Flags.trainingUnits].wire)
                NumberEntry(Res.string.training_bar, TrainingEngine.mass(settings[Flags.trainingBar], MassUnit.Kg, settings[Flags.trainingUnits]), allowBlank = false, allowZero = true) { it?.let { set(Flags.trainingBar, TrainingEngine.kg(it, settings[Flags.trainingUnits])) } }
                PlateInventoryEditor(settings[Flags.trainingPlates], settings[Flags.trainingUnits]) { set(Flags.trainingPlates, it) }
            } }
            item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HelpLabel(stringResource(Res.string.training_maxes), Res.string.help_maxes, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.training_maxes_hint))
                catalog.filter { it.id in settings[Flags.trainingMaxes] || it.id in settings[Flags.trainingTms] }.forEach { exercise ->
                    TextButton({ chosen = exercise
                        maximum = settings[Flags.trainingMaxes][exercise.id]?.let { TrainingEngine.mass(it, MassUnit.Kg, settings[Flags.trainingUnits]) }
                        tm = settings[Flags.trainingTms][exercise.id]?.let { TrainingEngine.mass(it, MassUnit.Kg, settings[Flags.trainingUnits]) }
                    }) { Text(exercise.title) }
                }
                OutlinedButton({ maxEditor = true }) { Text(stringResource(Res.string.training_add_exercise)) }
            } }
            item { Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(Res.string.training_notification_hint))
                TextButton({ scope.launch { bridge.requestWorkoutNotifications(force = true) } }) { Text(stringResource(Res.string.training_notification_enable)) }
                HelpLabel(stringResource(Res.string.training_exact_timers), Res.string.help_exact_alarms)
                Text(stringResource(Res.string.training_exact_hint))
                TextButton({ bridge.requestExactTimers() }) { Text(stringResource(Res.string.training_enable_exact)) }
            } }
            problem?.let { item { Text(stringResource(Res.string.status_error, it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (maxEditor) ExercisePicker(catalog.filter { it.tracking.hasWeight }, { maxEditor = false }) { chosen = it; maximum = null; tm = null }
    chosen?.let { exercise -> AlertDialog(onDismissRequest = { chosen = null }, title = { Text(exercise.title + " · " + settings[Flags.trainingUnits].wire) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberEntry(Res.string.training_one_rm, maximum, help = Res.string.help_maxes) { maximum = it }
            NumberEntry(Res.string.training_training_max, tm, help = Res.string.help_maxes) { tm = it }
        } }, confirmButton = { TextButton({
            val key = exercise.id!!
            set(Flags.trainingMaxes, settings[Flags.trainingMaxes] - key + maximum?.let { mapOf(key to TrainingEngine.kg(it, settings[Flags.trainingUnits])) }.orEmpty())
            set(Flags.trainingTms, settings[Flags.trainingTms] - key + tm?.let { mapOf(key to TrainingEngine.kg(it, settings[Flags.trainingUnits])) }.orEmpty())
            chosen = null
        }) { Text(stringResource(Res.string.action_save)) } }, dismissButton = { TextButton({ chosen = null }) { Text(stringResource(Res.string.action_cancel)) } }) }
}
