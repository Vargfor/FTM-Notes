package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.vargfor.ftmnotes.core.model.MassUnit
import io.github.vargfor.ftmnotes.core.training.TrainingEngine
import io.github.vargfor.ftmnotes.resources.*
import org.jetbrains.compose.resources.*

/** Keep the compact settings format internal; people configure labelled weights and quantities. */
@Composable internal fun PlateInventoryEditor(value: String, unit: MassUnit, save: (String) -> Unit) {
    val plates = remember(value) { value.split(',').mapNotNull { row ->
        val parts = row.split(':')
        val kg = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val count = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        kg to count
    } }
    var editing by remember { mutableStateOf<Int?>(null) }
    var weightText by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("") }
    fun edit(index: Int) {
        val plate = plates.getOrNull(index)
        weightText = plate?.let { number(TrainingEngine.mass(it.first, MassUnit.Kg, unit)) }.orEmpty()
        countText = (plate?.second ?: 2).toString()
        editing = index
    }
    Text(stringResource(Res.string.training_plates), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(Res.string.plate_inventory_hint), style = MaterialTheme.typography.bodyMedium)
    plates.forEachIndexed { index, plate ->
        ListItem(headlineContent = { Text(number(TrainingEngine.mass(plate.first, MassUnit.Kg, unit)) + " " + unit.wire) },
            supportingContent = { Text(stringResource(Res.string.plate_quantity, plate.second)) },
            trailingContent = { TextButton({ edit(index) }) { Text(stringResource(Res.string.ic_edit_label)) } })
    }
    OutlinedButton({ edit(plates.size) }) { Text(stringResource(Res.string.plate_add)) }
    editing?.let { index ->
        val weight = weightText.replace(',', '.').toDoubleOrNull()
        val count = countText.toIntOrNull()
        val kg = weight?.let { TrainingEngine.kg(it, unit) }
        val duplicate = kg != null && plates.withIndex().any { it.index != index && kotlin.math.abs(it.value.first - kg) < 0.000001 }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(Res.string.plate_edit)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(unit.wire)
                OutlinedTextField(weightText, { weightText = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.plate_weight)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(countText, { countText = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.plate_count)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (duplicate) Text(stringResource(Res.string.plate_duplicate), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton({
                val updated = plates.toMutableList()
                val original = plates.getOrNull(index)
                val storedKg = if (original != null && weightText == number(TrainingEngine.mass(original.first, MassUnit.Kg, unit))) original.first else kg!!
                if (index < updated.size) updated[index] = storedKg to count!! else updated += storedKg to count!!
                save(updated.sortedByDescending { it.first }.joinToString(",") { "${it.first}:${it.second}" })
                editing = null
            }, enabled = kg != null && kg.isFinite() && kg > 0 && count != null && count >= 0 && !duplicate) {
                Text(stringResource(Res.string.action_done))
            }
        }, dismissButton = { TextButton({ editing = null }) { Text(stringResource(Res.string.action_cancel)) } })
    }
}
