package io.github.vargfor.ftmnotes.feature.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.food.engine.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.feature.settings.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@Composable
fun FoodSettingsScreen(onBack: () -> Unit) {
    val repository: SettingsRepository = koinInject()
    val settings by repository.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var densityEditor by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editDensity by remember { mutableStateOf<Double?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun <T> set(flag: FeatureFlag<T>, value: T) { scope.launch { try { repository.set(flag, value) } catch (e: Exception) { problem = e.message } } }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.food_settings), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ChoiceRow(stringResource(Res.string.food_target_units), UnitMode.entries, settings[Flags.foodUnits], { unitLabel(it) }, { set(Flags.foodUnits, it) }) }
            item { ChoiceRow(stringResource(Res.string.food_volume_units), MetricVolume.entries, settings[Flags.foodVolume], { stringResource(when (it) { MetricVolume.Auto -> Res.string.food_volume_auto; MetricVolume.Millilitres -> Res.string.food_volume_ml; MetricVolume.Decilitres -> Res.string.food_volume_dl }) }, help = Res.string.help_metric_volume, onSelect = { set(Flags.foodVolume, it) }) }
            item { SwitchRow(stringResource(Res.string.food_keep_spoons), settings[Flags.foodKeepSpoons], { set(Flags.foodKeepSpoons, it) }, help = Res.string.help_spoons) }
            item { SwitchRow(stringResource(Res.string.food_density_setting), settings[Flags.foodUseDensities], { set(Flags.foodUseDensities, it) }, help = Res.string.help_density) }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpLabel(stringResource(Res.string.food_edit_densities), Res.string.help_density, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.food_density_source), style = MaterialTheme.typography.bodySmall)
                    (Densities.gramsPerCup.keys + settings[Flags.foodDensities].keys).distinct().forEach { name ->
                        val value = Densities.find(name, settings[Flags.foodDensities])!!
                        Row {
                            TextButton({ editName = name; editDensity = value; densityEditor = true }, Modifier.weight(1f)) {
                                Text(name + ": " + QuantityFormatter.decimal(value, digits = 4) + " g/ml")
                            }
                            if (name in settings[Flags.foodDensities]) TextButton({ set(Flags.foodDensities, settings[Flags.foodDensities] - name) }) { Text(stringResource(Res.string.food_density_reset)) }
                        }
                    }
                    OutlinedButton({ editName = ""; editDensity = null; densityEditor = true }) { Text(stringResource(Res.string.food_density_add)) }
                }
            }
            item { ChoiceRow(stringResource(Res.string.food_rounding), FoodRounding.entries, settings[Flags.foodRounding], { stringResource(when (it) { FoodRounding.Kitchen -> Res.string.food_round_kitchen; FoodRounding.Exact -> Res.string.food_round_exact; FoodRounding.Custom -> Res.string.food_round_custom }) }, help = Res.string.help_rounding, onSelect = { set(Flags.foodRounding, it) }) }
            if (settings[Flags.foodRounding] == FoodRounding.Custom) item {
                NumberEntry(Res.string.food_round_step, settings[Flags.foodRoundingStep], Modifier.padding(horizontal = 16.dp), allowBlank = false, help = Res.string.help_rounding) { it?.let { set(Flags.foodRoundingStep, it) } }
            }
            listOf(Flags.foodStepConversions to Res.string.food_step_conversions, Flags.foodFanHint to Res.string.food_fan_hint,
                Flags.foodKeepAwake to Res.string.food_keep_awake, Flags.foodIngredientChecks to Res.string.food_ingredient_checks,
                Flags.foodTimers to Res.string.food_timers_setting, Flags.foodMultipleLists to Res.string.food_multiple_lists).forEach { (flag, label) ->
                item { SwitchRow(stringResource(label), settings[flag], { set(flag, it) }, help = when(flag) {
                    Flags.foodStepConversions -> Res.string.help_step_conversion
                    Flags.foodFanHint -> Res.string.food_fan_explain
                    else -> null
                }) }
            }
            problem?.let { item { Text(stringResource(Res.string.status_error, it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (densityEditor) AlertDialog(onDismissRequest = { densityEditor = false }, title = { Text(stringResource(Res.string.food_edit_densities)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(editName, { editName = it }, label = { Text(stringResource(Res.string.food_density_name)) })
            NumberEntry(Res.string.food_density_grams_ml, editDensity, allowBlank = false, help = Res.string.help_density) { editDensity = it }
        } },
        confirmButton = { TextButton({ val name = Densities.key(editName); val value = editDensity
            if (name.isNotBlank() && value != null && value > 0) { set(Flags.foodDensities, settings[Flags.foodDensities] + (name to value)); densityEditor = false }
        }, enabled = editName.isNotBlank() && editDensity != null) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton({ densityEditor = false }) { Text(stringResource(Res.string.action_cancel)) } })
}
