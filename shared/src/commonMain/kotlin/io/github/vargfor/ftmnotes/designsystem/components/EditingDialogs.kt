package io.github.vargfor.ftmnotes.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.vargfor.ftmnotes.core.model.NoteColor
import io.github.vargfor.ftmnotes.resources.*
import org.jetbrains.compose.resources.*

@Composable
fun ActionIcon(icon: DrawableResource, label: StringResource, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), stringResource(label), tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

@Composable
fun TextEntryDialog(title: StringResource, initial: String = "", allowBlank: Boolean = false, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) },
        text = { OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(value.trim()); onDismiss() }, enabled = allowBlank || value.isNotBlank()) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.action_cancel)) } })
}

@Composable
fun ConfirmPurgeDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.notes_purge_title)) },
        text = { Text(stringResource(Res.string.notes_purge_body)) },
        confirmButton = { TextButton({ onConfirm(); onDismiss() }) { Text(stringResource(Res.string.action_delete_forever)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.action_cancel)) } })
}

@Composable
fun noteBackground(wire: String?): Color {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val note = NoteColor.fromWire(wire) ?: return base
    return lerp(base, Color(0xff000000L or note.referenceRgb.toLong()), 0.22f)
}

private val NoteColor.label: StringResource get() = when (this) {
    NoteColor.Red -> Res.string.color_red; NoteColor.Orange -> Res.string.color_orange
    NoteColor.Yellow -> Res.string.color_yellow; NoteColor.Green -> Res.string.color_green
    NoteColor.Teal -> Res.string.color_teal; NoteColor.Blue -> Res.string.color_blue
    NoteColor.Cerulean -> Res.string.color_cerulean; NoteColor.Purple -> Res.string.color_purple
    NoteColor.Pink -> Res.string.color_pink; NoteColor.Brown -> Res.string.color_brown
    NoteColor.Gray -> Res.string.color_gray
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorDialog(onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(Res.string.action_color)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ onChoose(null); onDismiss() }) { Text(stringResource(Res.string.notes_default_color)) }
                NoteColor.entries.forEach { color ->
                    FilledTonalButton({ onChoose(color.wire); onDismiss() }, contentPadding = PaddingValues(12.dp)) {
                        Box(Modifier.size(16.dp).background(noteBackground(color.wire), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(color.label))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(Res.string.action_close)) } })
}
