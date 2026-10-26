package io.github.vargfor.ftmnotes.feature.importer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.backup.readLimited
import io.github.vargfor.ftmnotes.core.backup.attachmentRefs
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.platform.*
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import okio.*
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@Composable
fun ImportHomeScreen(onBack: () -> Unit, onPreview: (String) -> Unit, onFoodImport: () -> Unit = {}) {
    val imports: ImportCoordinator = koinInject()
    val bridge = koinInject<AppPlatform>().bridge
    val scope = rememberCoroutineScope()
    val history by imports.history.collectAsStateWithLifecycle(emptyList())
    var pasted by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val links = stringResource(Res.string.import_links_heading)
    val undone = stringResource(Res.string.import_undone)
    fun prepare(block: suspend () -> ImportPreview) {
        scope.launch {
            busy = true; problem = null
            try { onPreview(block().id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = e.message }
            finally { busy = false }
        }
    }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.import_title), onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(Res.string.import_keep_help), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton({ prepare {
                    val files = bridge.pickFiles()
                    if (files.isEmpty()) throw CancellationException()
                    imports.prepareKeep(files, links)
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.import_keep)) }
            }
            item {
                OutlinedButton({ prepare {
                    val files = bridge.pickFiles()
                    if (files.isEmpty()) throw CancellationException()
                    require(files.size == 1) { "Choose one FTM file" }
                    val text = withContext(Dispatchers.IO) { FileSystem.SYSTEM.source(files.single().path).buffer().use { it.readLimited(64L * 1024 * 1024).decodeToString() } }
                    imports.prepareFtm(text)
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.import_ftm_file)) }
            }
            item {
                Text(stringResource(Res.string.import_ai), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.import_prompt), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onFoodImport) { Text(stringResource(Res.string.food_recipe_prompt)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ scope.launch {
                        try { bridge.copyText(Res.readBytes("files/AI_PROMPT_note.md").decodeToString()); snackbar.showSnackbar(getString(Res.string.import_prompt_copied)) }
                        catch (e: Exception) { problem = e.message }
                    } }) { Text(stringResource(Res.string.import_copy_prompt)) }
                    OutlinedButton({ scope.launch {
                        try { bridge.shareText(Res.readBytes("files/AI_PROMPT_note.md").decodeToString()) }
                        catch (e: Exception) { problem = e.message }
                    } }) { Text(stringResource(Res.string.import_share_prompt)) }
                }
            }
            item {
                OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    label = { Text(stringResource(Res.string.import_paste)) }, supportingText = { Text(stringResource(Res.string.import_clipboard)) })
                Button({ prepare { imports.prepareFtm(pasted) } }, enabled = !busy && pasted.isNotBlank()) { Text(stringResource(Res.string.import_preview)) }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            problem?.let { message -> item { Text(stringResource(Res.string.status_error, message), color = MaterialTheme.colorScheme.error) } }
            if (history.isNotEmpty()) item { Text(stringResource(Res.string.import_history), style = MaterialTheme.typography.titleLarge) }
            items(history, key = { it.id }) { batch ->
                ListItem(headlineContent = { Text(batch.source) }, supportingContent = { Text(stringResource(Res.string.import_complete, batch.imported)) },
                    trailingContent = { TextButton({ scope.launch {
                        try { imports.undo(batch.id); snackbar.showSnackbar(undone) } catch (e: Exception) { problem = e.message }
                    } }, enabled = batch.status != "undone") { Text(stringResource(Res.string.import_undo)) } })
            }
        }
    }
}

@Composable
fun ImportPreviewScreen(id: String, onBack: () -> Unit) {
    val imports: ImportCoordinator = koinInject()
    var preview by remember(id) { mutableStateOf<ImportPreview?>(null) }
    var selected by rememberSaveable(id) { mutableStateOf<List<String>?>(null) }
    var tags by rememberSaveable(id) { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var imported by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    var undone by rememberSaveable(id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        try {
            val loaded = imports.preview(id); preview = loaded
            if (selected == null) selected = loaded.drafts.filterNot { it.duplicate }.map { it.id }
        } catch (e: Exception) { problem = e.message }
    }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.import_preview), onBack) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (imported == null) Button({
                        scope.launch { busy = true
                            try { imported = imports.commit(id, selected.orEmpty().toSet(), tags.split(',').map(String::trim).filter(String::isNotEmpty)).imported }
                            catch (e: Exception) { problem = e.message }
                            finally { busy = false }
                        }
                    }, enabled = !busy && selected.orEmpty().isNotEmpty(), modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.import_commit)) }
                    else {
                        Text(stringResource(if (undone) Res.string.import_undone else Res.string.import_complete, imported!!), Modifier.weight(1f))
                        TextButton({ scope.launch { try { imports.undo(id); undone = true } catch (e: Exception) { problem = e.message } } }, enabled = !undone) { Text(stringResource(Res.string.import_undo)) }
                    }
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val current = preview
            if (current == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else {
                item {
                    Text(stringResource(Res.string.import_selected, selected.orEmpty().size, current.drafts.size), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.import_skipped, current.skipped), style = MaterialTheme.typography.bodySmall)
                    if (imported == null) {
                        TextButton({ selected = if (selected.orEmpty().size == current.drafts.size) emptyList() else current.drafts.map { it.id } }) { Text(stringResource(Res.string.action_select_all)) }
                        OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.import_tags)) })
                    }
                }
                if (current.drafts.isEmpty()) item { Text(stringResource(Res.string.import_no_items)) }
                if (current.issues.isNotEmpty()) item {
                    Text(stringResource(Res.string.import_issues), style = MaterialTheme.typography.titleMedium)
                    current.issues.forEach { issue -> Text("${issue.path}: ${issue.message}", style = MaterialTheme.typography.bodySmall,
                        color = if (issue.severity == ReadIssue.Severity.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(current.drafts, key = { it.id }) { draft ->
                    ElevatedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(draft.id in selected.orEmpty(), enabled = imported == null, onCheckedChange = {
                                    selected = if (it) selected.orEmpty() + draft.id else selected.orEmpty() - draft.id
                                })
                                Text(draft.document.title.ifBlank { stringResource(Res.string.note_untitled) }, style = MaterialTheme.typography.titleMedium)
                            }
                            if (draft.duplicate) Text(stringResource(Res.string.import_duplicate), color = MaterialTheme.colorScheme.primary)
                            Text(draft.document.plainText().take(600), style = MaterialTheme.typography.bodyMedium)
                            AttachmentList(draft.document.attachmentRefs())
                        }
                    }
                }
            }
            problem?.let { message -> item { Text(stringResource(Res.string.status_error, message), color = MaterialTheme.colorScheme.error) } }
        }
    }
}
