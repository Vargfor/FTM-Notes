package io.github.vargfor.ftmnotes.feature.importer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vargfor.ftmnotes.designsystem.components.HelpIcon
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.feature.settings.ChoiceRow
import io.github.vargfor.ftmnotes.feature.training.contentTypeLabel
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

/** All content types share the same portable AI/import workflow. */
@Composable fun PromptActions(initialType: DocumentType = DocumentType.Note, onError: (String?) -> Unit) {
    val bridge = koinInject<AppPlatform>().bridge
    var type by remember { mutableStateOf(initialType) }
    var menu by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            TextButton({ expanded = !expanded }) { Text(stringResource(Res.string.import_ai)) }
            HelpIcon(stringResource(Res.string.import_ai), Res.string.help_ai_import)
        }
        if (expanded) {
        Text(stringResource(Res.string.import_prompt))
        Text(stringResource(Res.string.import_content_type), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) { Text(contentTypeLabel(type)) }
            DropdownMenu(menu, { menu = false }) {
                DocumentType.entries.filter { it != DocumentType.Settings }.forEach { option ->
                    DropdownMenuItem({ Text(contentTypeLabel(option)) }, { type = option; copied = false; menu = false })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ scope.launch { try {
                bridge.copyText(Res.readBytes("files/AI_PROMPT_" + type.wire + ".md").decodeToString()); copied = true
            } catch (e: Exception) { onError(e.message) } } }) { Text(stringResource(Res.string.import_copy_prompt)) }
            OutlinedButton({ scope.launch { try {
                bridge.shareText(Res.readBytes("files/AI_PROMPT_" + type.wire + ".md").decodeToString())
            } catch (e: Exception) { onError(e.message) } } }) { Text(stringResource(Res.string.import_share_prompt)) }
        }
        if (copied) Text(stringResource(Res.string.import_prompt_copied), color = MaterialTheme.colorScheme.primary)
        }
    }
}
