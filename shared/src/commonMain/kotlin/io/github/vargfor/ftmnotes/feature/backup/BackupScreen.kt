package io.github.vargfor.ftmnotes.feature.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.backup.BackupArchive
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.platform.*
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okio.*
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import kotlin.time.Instant

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val backups: BackupService = koinInject()
    val settings: SettingsRepository = koinInject()
    val bridge = koinInject<AppPlatform>().bridge
    val snapshot by settings.snapshot.collectAsStateWithLifecycle()
    val status by backups.status.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var restoreFile by remember { mutableStateOf<Path?>(null) }
    var files by remember { mutableStateOf(emptyList<BackupFile>()) }
    val suffix = stringResource(Res.string.backup_conflict_suffix)
    fun work(block: suspend () -> Unit) {
        scope.launch { busy = true; problem = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = e.message }
            finally { busy = false }
        }
    }
    val folder = snapshot[Flags.backupFolder]
    suspend fun refresh() { files = if (folder.isBlank()) emptyList() else bridge.listBackups(folder) }
    LaunchedEffect(folder) { if (folder.isNotBlank()) try { refresh() } catch (e: Exception) { problem = e.message } }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.backup_title), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Button({ work {
                    val chosen = bridge.chooseBackupFolder()
                    if (chosen != null) {
                        if (bridge.canAutomateBackups) backups.configure(chosen)
                        else settings.set(Flags.backupFolder, chosen)
                    }
                } }, enabled = !busy) { Text(stringResource(Res.string.backup_folder)) }
                if (folder.isBlank()) Text(stringResource(Res.string.backup_folder_missing), style = MaterialTheme.typography.bodySmall)
            }
            if (bridge.canAutomateBackups) item {
                ListItem(headlineContent = { Text(stringResource(Res.string.backup_enabled)) },
                    trailingContent = { Switch(snapshot[Flags.backupEnabled], { enabled -> work { backups.enable(enabled) } }, enabled = !busy && folder.isNotBlank()) })
                Text(stringResource(Res.string.backup_retention), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Flags.backupRetention.options.forEach { count -> FilterChip(snapshot[Flags.backupRetention] == count,
                        { work { settings.set(Flags.backupRetention, count) } }, label = { Text(count.toString()) }) }
                }
            }
            item {
                val last = status.firstOrNull { it.key == "backup.last_success" }?.value?.let { runCatching { Json.parseToJsonElement(it).jsonPrimitive.long }.getOrNull() }
                Text(if (last == null) stringResource(Res.string.backup_no_success) else stringResource(Res.string.backup_last_success, Instant.fromEpochMilliseconds(last).toString()),
                    style = MaterialTheme.typography.bodySmall)
                status.firstOrNull { it.key == "backup.last_error" }?.let { Text(runCatching { Json.parseToJsonElement(it.value).jsonPrimitive.content }.getOrDefault(it.value), color = MaterialTheme.colorScheme.error) }
            }
            item {
                FilledTonalButton({ work { backups.backupToFolder(); message = getString(Res.string.backup_saved); refresh() } },
                    enabled = !busy && folder.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.backup_now)) }
                OutlinedButton({ work {
                    val archive = backups.create()
                    try { if (bridge.exportFile(archive, archive.name, "application/zip")) message = getString(Res.string.backup_saved) }
                    finally { withContext(Dispatchers.IO) { FileSystem.SYSTEM.delete(archive, mustExist = false) } }
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.backup_manual)) }
                OutlinedButton({ work {
                    val chosen = bridge.pickFiles()
                    if (chosen.isNotEmpty()) {
                        require(chosen.size == 1) { "Choose one backup" }
                        withContext(Dispatchers.IO) { BackupArchive.read(FileSystem.SYSTEM, chosen.single().path) }
                        restoreFile = chosen.single().path
                    }
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.backup_restore)) }
            }
            item {
                HorizontalDivider()
                Text(stringResource(Res.string.action_export), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.backup_json_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ work { val file = backups.exportFtm(); bridge.exportFile(file, file.name, "application/json") } }, enabled = !busy) {
                    Text(stringResource(Res.string.action_export))
                }
            }
            if (files.isNotEmpty()) item { Text(stringResource(Res.string.backup_view_files), style = MaterialTheme.typography.titleLarge) }
            items(files.sortedByDescending { it.name }, key = { it.reference }) { file ->
                ListItem(headlineContent = { Text(file.name) }, trailingContent = { TextButton({ work {
                    val selected = bridge.readBackup(file.reference)
                    withContext(Dispatchers.IO) { BackupArchive.read(FileSystem.SYSTEM, selected.path) }
                    restoreFile = selected.path
                } }, enabled = !busy) { Text(stringResource(Res.string.action_restore)) } })
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(Res.string.backup_running)) }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            problem?.let { item { Text(stringResource(Res.string.status_error, it), color = MaterialTheme.colorScheme.error) } }
        }
    }
    restoreFile?.let { file ->
        AlertDialog(onDismissRequest = { restoreFile = null }, title = { Text(stringResource(Res.string.backup_restore_confirm)) },
            text = { Text(stringResource(Res.string.backup_restore_explain)) },
            confirmButton = { TextButton({ restoreFile = null; work {
                val summary = backups.restore(file, suffix)
                message = getString(Res.string.backup_restored, summary.documents, summary.conflicts, summary.revisions)
            } }) { Text(stringResource(Res.string.action_restore)) } },
            dismissButton = { TextButton({ restoreFile = null }) { Text(stringResource(Res.string.action_cancel)) } })
    }
}
