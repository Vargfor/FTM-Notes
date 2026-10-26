package io.github.vargfor.ftmnotes.feature.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.notes.ChecklistOps
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.data.AttachmentStore
import io.github.vargfor.ftmnotes.data.DocumentRepository
import kotlinx.coroutines.launch
import okio.FileSystem
import io.github.vargfor.ftmnotes.core.settings.Flags
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.*

@Composable
fun NoteEditorScreen(noteId: String, onBack: () -> Unit, initialChecklist: Boolean = false, onCloned: (String) -> Unit = {}, viewModel: NoteEditorViewModel = koinViewModel { parametersOf(noteId) }) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }
    DisposableEffect(viewModel) { onDispose { viewModel.flush() } }
    val settings: SettingsRepository = koinInject()
    val snapshot by settings.snapshot.collectAsStateWithLifecycle()
    val draft = viewModel.draft
    val bridge = koinInject<AppPlatform>().bridge
    val store: AttachmentStore = koinInject()
    val repository: DocumentRepository = koinInject()
    val scope = rememberCoroutineScope()
    var attachmentError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.isLoaded) {
        if (viewModel.isLoaded && viewModel.isNew && initialChecklist && viewModel.draft.kind == NoteKind.Text) viewModel.changeKind()
    }
    fun addPhotos(camera: Boolean) {
        scope.launch {
            try {
                val files = if (camera) listOfNotNull(bridge.takePhoto()) else bridge.pickFiles(imagesOnly = true)
                val refs = files.map { store.put(FileSystem.SYSTEM.source(it.path), it.mime, it.name) }
                viewModel.edit(viewModel.draft.copy(attachments = (viewModel.draft.attachments + refs).distinctBy { it.sha256 }))
                viewModel.flush()
            } catch (e: Exception) { attachmentError = e.message }
        }
    }
    var menu by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    var labels by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = from.key as? String; val b = to.key as? String
        if (a != null && b != null && draft.items.any { it.id == a } && draft.items.any { it.id == b }) {
            viewModel.items({ ChecklistOps.move(it, a, b) })
        }
    }
    Scaffold(
        containerColor = noteBackground(draft.color),
        modifier = Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.Z) {
                if (it.isShiftPressed) viewModel.redo() else viewModel.undo()
                true
            } else false
        },
        topBar = { TopAppBar(title = {}, navigationIcon = { BackButton { viewModel.flush(); onBack() } }, actions = {
            ActionIcon(Res.drawable.ic_undo, Res.string.action_undo, viewModel.canUndo) { viewModel.undo() }
            ActionIcon(Res.drawable.ic_redo, Res.string.action_redo, viewModel.canRedo) { viewModel.redo() }
            ActionIcon(if (draft.pinned) Res.drawable.ic_push_pin_filled else Res.drawable.ic_push_pin, Res.string.action_pin, selected = draft.pinned) {
                viewModel.edit(draft.copy(pinned = !draft.pinned)); viewModel.flush()
            }
            Box {
                ActionIcon(Res.drawable.ic_more_vert, Res.string.action_more) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.action_duplicate)) }, onClick = { menu = false; scope.launch {
                        try {
                            if (viewModel.flushNow()) {
                                val title = getString(Res.string.notes_copy_title, viewModel.draft.title)
                                val id = repository.save(viewModel.draft.copy(id = null, title = title, createdAt = null, updatedAt = null, pinned = false, archived = false))
                                onCloned(id)
                            }
                        } catch (e: Exception) { attachmentError = e.message }
                    } })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.action_delete)) }, onClick = { menu = false; scope.launch {
                        try { if (viewModel.flushNow()) { repository.moveToTrash(noteId); onBack() } } catch (e: Exception) { attachmentError = e.message }
                    } })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.attachment_add)) }, onClick = { menu = false; addPhotos(false) })
                    if (bridge.canTakePhoto) DropdownMenuItem(text = { Text(stringResource(Res.string.attachment_camera)) }, onClick = { menu = false; addPhotos(true) })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.action_share)) }, onClick = { menu = false; scope.launch { try { bridge.shareText(draft.title + "\n" + draft.content) } catch (e: Exception) { attachmentError = e.message } } })
                    DropdownMenuItem(text = { Text(stringResource(if (draft.kind == NoteKind.Text) Res.string.notes_convert_checklist else Res.string.notes_convert_text)) },
                        onClick = { menu = false; viewModel.changeKind() })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.action_color)) }, onClick = { menu = false; colors = true })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.action_labels)) }, onClick = { menu = false; labels = true })
                    DropdownMenuItem(text = { Text(stringResource(if (draft.archived) Res.string.action_unarchive else Res.string.action_archive)) },
                        onClick = { menu = false; viewModel.edit(draft.copy(archived = !draft.archived)); viewModel.flush(); onBack() })
                    if (draft.kind == NoteKind.Checklist) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.notes_uncheck_all)) }, onClick = { menu = false; viewModel.items(ChecklistOps::uncheckAll) })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.notes_delete_checked)) }, onClick = { menu = false; viewModel.items(ChecklistOps::deleteChecked) })
                    }
                }
            }
        }) },
        bottomBar = {
            if (viewModel.isLoaded && draft.kind == NoteKind.Text) {
                Row(Modifier.fillMaxWidth().imePadding().horizontalScroll(rememberScrollState()).padding(4.dp)) {
                    ActionIcon(Res.drawable.ic_format_bold, Res.string.format_bold) { viewModel.rich.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
                    ActionIcon(Res.drawable.ic_format_italic, Res.string.format_italic) { viewModel.rich.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
                    ActionIcon(Res.drawable.ic_format_underlined, Res.string.format_underline) { viewModel.rich.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
                    ActionIcon(Res.drawable.ic_format_strikethrough, Res.string.format_strike) { viewModel.rich.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
                    ActionIcon(Res.drawable.ic_format_h1, Res.string.format_heading_one) { viewModel.rich.setHeadingStyle(HeadingStyle.H1) }
                    ActionIcon(Res.drawable.ic_format_h2, Res.string.format_heading_two) { viewModel.rich.setHeadingStyle(HeadingStyle.H2) }
                    ActionIcon(Res.drawable.ic_format_list_bulleted, Res.string.format_bullets) { viewModel.rich.toggleUnorderedList() }
                    ActionIcon(Res.drawable.ic_format_list_numbered, Res.string.format_numbers) { viewModel.rich.toggleOrderedList() }
                    ActionIcon(Res.drawable.ic_link, Res.string.notes_link) { link = true }
                    ActionIcon(Res.drawable.ic_code, Res.string.format_code) { viewModel.rich.toggleCodeSpan() }
                    ActionIcon(Res.drawable.ic_format_clear, Res.string.format_clear) { viewModel.rich.clearSpanStyles(); viewModel.rich.clearRichSpans(); viewModel.rich.setHeadingStyle(HeadingStyle.Normal) }
                }
            }
        },
    ) { padding ->
        if (viewModel.isLoaded) LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).then(if (draft.kind == NoteKind.Checklist) Modifier.imePadding() else Modifier),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "title") {
                BasicTextField(draft.title, { viewModel.edit(draft.copy(title = it), "title") }, Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner -> Box { if (draft.title.isEmpty()) Text(stringResource(Res.string.note_title_hint), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
            }
            if (draft.tags.isNotEmpty()) item(key = "labels") { Text(draft.tags.joinToString(" · "), color = MaterialTheme.colorScheme.primary) }
            viewModel.error?.let { problem -> item(key = "error") { Text(stringResource(Res.string.status_error, problem), color = MaterialTheme.colorScheme.error) } }
            attachmentError?.let { problem -> item(key = "attachment_error") { Text(stringResource(Res.string.status_error, problem), color = MaterialTheme.colorScheme.error) } }
            if (draft.attachments.isNotEmpty()) item(key = "attachments") { AttachmentList(draft.attachments) { sha -> viewModel.edit(draft.copy(attachments = draft.attachments.filterNot { it.sha256 == sha })); viewModel.flush() } }
            if (draft.kind == NoteKind.Text) item(key = "body") {
                RichTextEditor(state = viewModel.rich, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    placeholder = { Text(stringResource(Res.string.note_body_hint)) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = RichTextEditorDefaults.richTextEditorColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            } else {
                val ordered = if (snapshot[Flags.checkedSection]) draft.items.filterNot { it.checked } + draft.items.filter { it.checked } else draft.items
                items(ordered, key = { it.id!! }) { item ->
                    ReorderableItem(reorder, key = item.id!!) { _ ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = (item.indent * 24).dp)) {
                            Checkbox(item.checked, { checked -> viewModel.items({ ChecklistOps.setChecked(it, item.id!!, checked) }) })
                            OutlinedTextField(item.text, { text ->
                                viewModel.items({ ChecklistOps.splitLines(it, item.id!!, text, text.length).items }, "item/${item.id}")
                            }, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (item.checked) TextDecoration.LineThrough else null))
                            ActionIcon(if (item.indent == 0) Res.drawable.ic_format_indent_increase else Res.drawable.ic_format_indent_decrease,
                                if (item.indent == 0) Res.string.notes_indent else Res.string.notes_outdent) {
                                viewModel.items({ ChecklistOps.setIndent(it, item.id!!, 1 - item.indent) })
                            }
                            ActionIcon(Res.drawable.ic_close, Res.string.action_delete) { viewModel.items({ ChecklistOps.delete(it, item.id!!).items }) }
                            Icon(painterResource(Res.drawable.ic_drag_indicator), stringResource(Res.string.notes_drag), Modifier.size(48.dp).draggableHandle())
                        }
                    }
                }
                item(key = "add") {
                    TextButton({ viewModel.items({ it + ChecklistOps.newItem() }) }) {
                        Icon(painterResource(Res.drawable.ic_add), null); Text(stringResource(Res.string.notes_add_item))
                    }
                }
            }
        }
    }
    if (colors) ColorDialog({ colors = false }) { viewModel.edit(draft.copy(color = it)); viewModel.flush() }
    if (labels) TextEntryDialog(Res.string.action_labels, draft.tags.joinToString(", "), true, { labels = false }) {
        viewModel.edit(draft.copy(tags = it.split(',').map(String::trim).filter(String::isNotEmpty))); viewModel.flush()
    }
    if (link) TextEntryDialog(Res.string.notes_link_url, onDismiss = { link = false }) { url ->
        if (viewModel.rich.selection.collapsed) viewModel.rich.addLink(url, url) else viewModel.rich.addLinkToSelection(url)
    }
}
