package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vargfor.ftmnotes.core.model.ExerciseDocument
import io.github.vargfor.ftmnotes.core.training.ExerciseSearch
import io.github.vargfor.ftmnotes.resources.*
import org.jetbrains.compose.resources.*

internal val exerciseCategories = listOf("strength", "cardio", "stretching", "plyometrics", "powerlifting", "olympic weightlifting", "strongman")

/** Labels are shared by filters, exercise details and manual editing. Unknown user values stay visible. */
@Composable internal fun exerciseTerm(value: String): String {
    val label = when (value) {
        "strength" -> Res.string.exercise_strength; "cardio" -> Res.string.exercise_cardio
        "stretching" -> Res.string.exercise_stretching; "plyometrics" -> Res.string.exercise_plyometrics
        "powerlifting" -> Res.string.exercise_powerlifting; "olympic weightlifting" -> Res.string.exercise_weightlifting
        "strongman" -> Res.string.exercise_strongman
        "abdominals", "core" -> Res.string.exercise_abs; "quadriceps" -> Res.string.exercise_quadriceps
        "hamstrings" -> Res.string.exercise_hamstrings; "abductors" -> Res.string.exercise_abductors
        "adductors" -> Res.string.exercise_adductors; "lats" -> Res.string.exercise_lats
        "middle back" -> Res.string.exercise_middle_back; "lower back" -> Res.string.exercise_lower_back
        "traps" -> Res.string.exercise_traps; "glutes" -> Res.string.exercise_glutes
        "bodyweight" -> Res.string.exercise_bodyweight; "e-z curl bar" -> Res.string.exercise_ez_bar
        "foam roll" -> Res.string.exercise_foam_roll; "other" -> Res.string.exercise_other_equipment
        else -> null
    }
    return label?.let { stringResource(it) } ?: value.replaceFirstChar { it.titlecase() }
}

@Composable internal fun ExerciseOption(
    label: String, values: List<String>, selected: String?, modifier: Modifier = Modifier,
    emptyLabel: String = stringResource(Res.string.exercise_all), onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ open = true }) {
            Text(stringResource(Res.string.exercise_filter_value, label, selected?.let { exerciseTerm(it) } ?: emptyLabel))
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(emptyLabel) }, { onSelect(null); open = false })
            values.filter(String::isNotBlank).distinct().forEach { value ->
                DropdownMenuItem({ Text(exerciseTerm(value)) }, { onSelect(value); open = false })
            }
        }
    }
}

@Composable internal fun ExerciseBrowseControls(catalog: List<ExerciseDocument>, search: ExerciseSearch, change: (ExerciseSearch) -> Unit) {
    OutlinedTextField(search.query, { change(search.copy(query = it)) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(Res.string.training_search_exercises)) },
        placeholder = { Text(stringResource(Res.string.exercise_search_hint)) }, singleLine = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ExerciseOption(stringResource(Res.string.exercise_category), catalog.map { it.category }.distinct().sorted(), search.category) { change(search.copy(category = it)) }
        ExerciseOption(stringResource(Res.string.training_muscles), catalog.flatMap { it.muscleGroups }.distinct().sorted(), search.muscle) { change(search.copy(muscle = it)) }
        ExerciseOption(stringResource(Res.string.training_equipment), catalog.map { it.equipment }.distinct().sorted(), search.equipment) { change(search.copy(equipment = it)) }
        if (search != ExerciseSearch()) TextButton({ change(ExerciseSearch()) }) { Text(stringResource(Res.string.exercise_reset_filters)) }
    }
}

@Composable internal fun exerciseSummary(exercise: ExerciseDocument): String {
    val parts = listOf(exercise.category, exercise.equipment).filter(String::isNotBlank) + exercise.muscleGroups
    return parts.map { exerciseTerm(it) }.distinct().joinToString(" · ")
}
