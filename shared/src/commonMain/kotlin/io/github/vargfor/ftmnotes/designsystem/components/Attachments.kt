package io.github.vargfor.ftmnotes.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.vargfor.ftmnotes.core.model.AttachmentRef
import io.github.vargfor.ftmnotes.core.backup.readLimited
import io.github.vargfor.ftmnotes.core.backup.BackupArchive
import io.github.vargfor.ftmnotes.data.AttachmentStore
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okio.buffer
import okio.use
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@Composable
fun AttachmentThumbnail(sha: String, label: String? = null, modifier: Modifier = Modifier) {
    val store: AttachmentStore = koinInject()
    val bytes by produceState<ByteArray?>(null, sha) {
        value = withContext(Dispatchers.IO) { runCatching { store.source(sha).buffer().use { it.readLimited(BackupArchive.MAX_ENTRY_BYTES) } }.getOrNull() }
    }
    if (bytes != null) AsyncImage(bytes, label, modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp), contentScale = ContentScale.Fit)
    else Text(stringResource(Res.string.attachment_missing), modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
}

@Composable
fun AttachmentList(refs: List<AttachmentRef>, onRemove: ((String) -> Unit)? = null) {
    val store: AttachmentStore = koinInject()
    val bridge = koinInject<AppPlatform>().bridge
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        refs.forEach { ref ->
            ElevatedCard(Modifier.fillMaxWidth().cardOutline()) {
                Column(Modifier.padding(8.dp)) {
                    if (ref.isImage) AttachmentThumbnail(ref.sha256, ref.name, Modifier.clickable { scope.launch {
                        try { bridge.openFile(store.path(ref.sha256), ref.mimeType) } catch (e: Exception) { error = e.message }
                    } })
                    Row {
                        TextButton({ scope.launch {
                            try { bridge.openFile(store.path(ref.sha256), ref.mimeType) } catch (e: Exception) { error = e.message }
                        } }, Modifier.weight(1f)) { Text(ref.name ?: stringResource(Res.string.attachment_file), maxLines = 1) }
                        onRemove?.let { ActionIcon(Res.drawable.ic_close, Res.string.action_delete) { it(ref.sha256) } }
                    }
                }
            }
        }
        error?.let { Text(stringResource(Res.string.status_error, it), color = MaterialTheme.colorScheme.error) }
    }
}
