package io.github.vargfor.ftmnotes.feature.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun NotesListScreen(
    onOpenNote: (String) -> Unit, onNewNote: () -> Unit,
    onOpenSearch: () -> Unit, onOpenSettings: () -> Unit,
    onOpenArchive: () -> Unit = {}, onOpenTrash: () -> Unit = {}, onOpenImport: () -> Unit = {},
    onNewChecklist: () -> Unit = onNewNote,
    mode: NoteCollection = NoteCollection.Active, onBack: () -> Unit = {},
    documentType: DocumentType = DocumentType.Note, contentHeader: @Composable () -> Unit = {},
    canCreate: Boolean = true,
    viewModel: NotesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val labels by viewModel.tags.collectAsStateWithLifecycle()
    val settings: SettingsRepository = koinInject()
    val snapshot by settings.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var tag by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    var editLabels by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var purge by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val changed = stringResource(Res.string.notes_changed)
    val undo = stringResource(Res.string.action_undo)
    val rows = (state as? NotesUiState.Ready)?.notes.orEmpty()
    val createLabel = stringResource(when (documentType) {
        DocumentType.Recipe -> Res.string.food_new_recipe
        DocumentType.ShoppingList -> Res.string.shopping_new
        else -> Res.string.notes_new
    })
    LaunchedEffect(mode, tag, documentType) { viewModel.filter(mode, tag, documentType) }
    LaunchedEffect(rows.map { it.id }) { selected = selected.intersect(rows.map { it.id }.toSet()) }
    fun mutate(mutation: ListMutation, ids: Set<String> = selected) {
        scope.launch {
            try {
                val revert = viewModel.mutate(ids, mutation)
                selected = emptySet()
                if (snackbar.showSnackbar(changed, undo) == SnackbarResult.ActionPerformed) revert()
            } catch (e: Exception) { problem = e.message }
        }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) BackTopBar(stringResource(Res.string.action_selected, selected.size), { selected = emptySet() }) {
                ActionIcon(Res.drawable.ic_push_pin, Res.string.action_pin) { mutate(ListMutation(pinned = !rows.filter { it.id in selected }.all { it.pinned })) }
                if (mode == NoteCollection.Trash) {
                    ActionIcon(Res.drawable.ic_unarchive, Res.string.action_restore) { mutate(ListMutation(trashed = false)) }
                    ActionIcon(Res.drawable.ic_delete, Res.string.action_delete_forever) { purge = true }
                } else {
                    ActionIcon(Res.drawable.ic_palette, Res.string.action_color) { colors = true }
                    ActionIcon(Res.drawable.ic_label, Res.string.action_labels) { editLabels = true }
                    ActionIcon(Res.drawable.ic_archive, Res.string.action_archive) { mutate(ListMutation(archived = mode != NoteCollection.Archive)) }
                    ActionIcon(Res.drawable.ic_delete, Res.string.action_delete) { mutate(ListMutation(trashed = true)) }
                }
            } else if (mode == NoteCollection.Active) SectionTopBar(
                stringResource(if (documentType == DocumentType.Note) Res.string.tab_notes else Res.string.tab_food),
                stringResource(if (documentType == DocumentType.Note) Res.string.notes_subtitle else Res.string.food_subtitle), onOpenSearch, onOpenSettings, scroll,
                extraActions = {
                    ActionIcon(if (snapshot[Flags.notesLayout] == NotesLayout.Grid) Res.drawable.ic_view_agenda else Res.drawable.ic_grid_view,
                        if (snapshot[Flags.notesLayout] == NotesLayout.Grid) Res.string.notes_list else Res.string.notes_grid) {
                        scope.launch { settings.set(Flags.notesLayout, if (snapshot[Flags.notesLayout] == NotesLayout.Grid) NotesLayout.List else NotesLayout.Grid) }
                    }
                    Box {
                        ActionIcon(Res.drawable.ic_more_vert, Res.string.action_more) { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            if (documentType == DocumentType.Note) DropdownMenuItem({ Text(stringResource(Res.string.notes_new_checklist)) }, { menu = false; onNewChecklist() })
                            DropdownMenuItem({ Text(stringResource(Res.string.notes_archive)) }, { menu = false; onOpenArchive() })
                            DropdownMenuItem({ Text(stringResource(Res.string.notes_trash)) }, { menu = false; onOpenTrash() })
                            DropdownMenuItem({ Text(stringResource(Res.string.action_import)) }, { menu = false; onOpenImport() })
                            DropdownMenuItem({ Text(stringResource(Res.string.notes_manage_labels)) }, { menu = false; manage = true })
                            DropdownMenuItem({ Text(stringResource(Res.string.action_select_all)) }, { menu = false; selected = rows.map { it.id }.toSet() }, enabled = rows.isNotEmpty())
                        }
                    }
                })
            else BackTopBar(stringResource(if (mode == NoteCollection.Archive) Res.string.notes_archive else Res.string.notes_trash), onBack) {
                if (mode == NoteCollection.Trash) TextButton({ selected = rows.map { it.id }.toSet(); purge = true }, enabled = rows.isNotEmpty()) { Text(stringResource(Res.string.action_empty_trash)) }
                else TextButton({ selected = rows.map { it.id }.toSet() }, enabled = rows.isNotEmpty()) { Text(stringResource(Res.string.action_select_all)) }
            }
        },
        floatingActionButton = { if (canCreate && mode == NoteCollection.Active && selected.isEmpty()) ExtendedFloatingActionButton(onClick = onNewNote, icon = { Icon(painterResource(Res.drawable.ic_add), createLabel) }, text = { Text(createLabel) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            contentHeader()
            if (mode != NoteCollection.Trash && labels.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { label -> FilterChip(tag == label.tag, { tag = if (tag == label.tag) null else label.tag }, label = { Text(label.tag) }) }
            }
            if (state is NotesUiState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (rows.isEmpty()) EmptyState(painterResource(Res.drawable.ic_sticky_note_2), stringResource(Res.string.notes_empty_collection),
                stringResource(Res.string.notes_empty_body))
            else LazyVerticalGrid(
                columns = if (snapshot[Flags.notesLayout] == NotesLayout.Grid) GridCells.Adaptive(160.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) else GridCells.Fixed(1),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val groups = if (rows.any { it.pinned } && mode != NoteCollection.Trash) listOf(
                    Res.string.notes_pinned to rows.filter { it.pinned }, Res.string.notes_others to rows.filterNot { it.pinned })
                    else listOf(null to rows)
                groups.forEach { (header, notes) ->
                    if (header != null && notes.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(header), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(notes, key = { it.id }) { row ->
                        val card: @Composable () -> Unit = {
                            DocumentCard(row, row.id in selected,
                                onClick = {
                                    if (selected.isNotEmpty() || mode == NoteCollection.Trash) selected = if (row.id in selected) selected - row.id else selected + row.id
                                    else onOpenNote(row.id)
                                }, onLongClick = { selected = selected + row.id })
                        }
                        if (snapshot[Flags.swipeToArchive] && selected.isEmpty() && mode == NoteCollection.Active) {
                            val swipe = rememberSwipeToDismissBoxState(confirmValueChange = {
                                if (it != SwipeToDismissBoxValue.Settled) mutate(ListMutation(archived = true), setOf(row.id))
                                false
                            })
                            SwipeToDismissBox(state = swipe, backgroundContent = {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(painterResource(Res.drawable.ic_archive), stringResource(Res.string.action_archive)) }
                            }) { card() }
                        } else card()
                    }
                }
            }
        }
    }
    if (colors) ColorDialog({ colors = false }) { mutate(ListMutation(color = ColorChange(it))) }
    if (editLabels) TextEntryDialog(Res.string.action_labels, allowBlank = true, onDismiss = { editLabels = false }) {
        mutate(ListMutation(tags = it.split(',').map(String::trim).filter(String::isNotEmpty)))
    }
    if (purge) ConfirmPurgeDialog({ purge = false }) { scope.launch { try { viewModel.purge(selected); selected = emptySet() } catch (e: Exception) { problem = e.message } } }
    if (manage) AlertDialog(onDismissRequest = { manage = false }, title = { Text(stringResource(Res.string.notes_manage_labels)) },
        text = { Column { labels.forEach { label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ renaming = label.tag }) { Text(label.tag, Modifier.weight(1f)) }
                ActionIcon(Res.drawable.ic_delete, Res.string.action_delete) { scope.launch { viewModel.deleteTag(label.tag) } }
            }
        } } }, confirmButton = { TextButton({ manage = false }) { Text(stringResource(Res.string.action_done)) } })
    renaming?.let { original ->
        TextEntryDialog(Res.string.notes_rename_label, original, onDismiss = { renaming = null }) { replacement ->
            scope.launch { viewModel.renameTag(original, replacement); if (tag == original) tag = replacement }
        }
    }
    problem?.let { message -> AlertDialog(onDismissRequest = { problem = null }, text = { Text(stringResource(Res.string.status_error, message)) },
        confirmButton = { TextButton({ problem = null }) { Text(stringResource(Res.string.action_close)) } }) }
}

@Composable
fun DocumentCard(row: DocumentListRow, selected: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = noteBackground(row.color)),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            row.thumbSha?.let { AttachmentThumbnail(it) }
            if (row.pinned) Icon(painterResource(Res.drawable.ic_push_pin_filled), stringResource(Res.string.action_pin), Modifier.size(18.dp))
            Text(row.title.ifBlank { stringResource(Res.string.note_untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.preview.isNotBlank()) Text(row.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            if (row.itemCount > 0) Text("${row.checkedCount}/${row.itemCount}", style = MaterialTheme.typography.labelSmall)
            row.tags?.takeIf { it.isNotBlank() }?.let { Text(it.replace(TAG_SEPARATOR.toString(), " · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
fun NoteSelectionPlaceholder() {
    EmptyState(painterResource(Res.drawable.ic_sticky_note_2), null, stringResource(Res.string.note_select_hint))
}
