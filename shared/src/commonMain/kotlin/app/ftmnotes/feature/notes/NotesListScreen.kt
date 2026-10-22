package app.ftmnotes.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ftmnotes.designsystem.components.EmptyState
import app.ftmnotes.designsystem.components.SectionTopBar
import app.ftmnotes.resources.Res
import app.ftmnotes.resources.ic_add
import app.ftmnotes.resources.ic_sticky_note_2
import app.ftmnotes.resources.note_select_hint
import app.ftmnotes.resources.note_untitled
import app.ftmnotes.resources.notes_empty_body
import app.ftmnotes.resources.notes_empty_title
import app.ftmnotes.resources.notes_new
import app.ftmnotes.resources.notes_subtitle
import app.ftmnotes.resources.tab_notes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun NotesListScreen(
    onOpenNote: (String) -> Unit,
    onNewNote: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: NotesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    // Collapse the FAB to an icon once the user scrolls, so it never hides content for long.
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SectionTopBar(
                title = stringResource(Res.string.tab_notes),
                subtitle = stringResource(Res.string.notes_subtitle),
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewNote,
                expanded = fabExpanded,
                icon = { Icon(painterResource(Res.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(Res.string.notes_new)) },
            )
        },
    ) { padding ->
        when (val current = state) {
            NotesUiState.Loading -> Unit
            is NotesUiState.Ready -> if (current.notes.isEmpty()) {
                EmptyState(
                    icon = painterResource(Res.drawable.ic_sticky_note_2),
                    title = stringResource(Res.string.notes_empty_title),
                    body = stringResource(Res.string.notes_empty_body),
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 96.dp, // room for the FAB
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current.notes, key = { it.id }) { note ->
                        NoteCard(note, onClick = { onOpenNote(note.id) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteListItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = note.title.ifBlank { stringResource(Res.string.note_untitled) },
                style = MaterialTheme.typography.titleMedium,
                color = if (note.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.snippet.isNotBlank()) {
                Text(
                    text = note.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Shown in the detail pane of two-pane layouts before a note is chosen. */
@Composable
fun NoteSelectionPlaceholder() {
    EmptyState(
        icon = painterResource(Res.drawable.ic_sticky_note_2),
        title = null,
        body = stringResource(Res.string.note_select_hint),
    )
}
