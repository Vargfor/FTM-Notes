package io.github.vargfor.ftmnotes.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.vargfor.ftmnotes.resources.*
import org.jetbrains.compose.resources.*

/** Default on for previews and standalone screens; the app supplies the device preference. */
val LocalHelpIcons = staticCompositionLocalOf { true }

/** A small glyph with a full touch target. Opening help never activates the surrounding control. */
@Composable
fun HelpIcon(title: String, body: StringResource, modifier: Modifier = Modifier) {
    if (!LocalHelpIcons.current) return
    var open by rememberSaveable(title, body) { mutableStateOf(false) }
    IconButton({ open = true }, modifier.size(48.dp)) {
        Icon(painterResource(Res.drawable.ic_info), stringResource(Res.string.help_for, title),
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(title) },
        text = { Text(stringResource(body), Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ open = false }) { Text(stringResource(Res.string.action_close)) } })
}

@Composable
fun HelpLabel(title: String, body: StringResource?, modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false), style = style)
        if (body != null) HelpIcon(title, body)
    }
}
