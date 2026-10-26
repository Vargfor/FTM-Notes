package io.github.vargfor.ftmnotes.feature.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.data.db.DocumentListRow
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.feature.notes.DocumentCard
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (String, DocumentType) -> Unit = { _, _ -> }) {
    val repository: DocumentRepository = koinInject()
    var query by rememberSaveable { mutableStateOf("") }
    var typeName by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var color by rememberSaveable { mutableStateOf<String?>(null) }
    var archive by rememberSaveable { mutableStateOf(true) }
    var colorDialog by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<DocumentListRow>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val labels by repository.observeTagCounts().collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(repository) {
        snapshotFlow { query to SearchFilters(typeName?.let(DocumentType::fromWire), tag, color, archive) }
            .debounce(200).distinctUntilChanged().collectLatest { (q, filters) ->
                try { repository.search(q, filters).collect { results = it; error = null } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
            }
    }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.search_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(stringResource(Res.string.search_query)) }, singleLine = true,
                trailingIcon = { if (query.isNotEmpty()) ActionIcon(Res.drawable.ic_close, Res.string.action_clear) { query = "" } })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(typeName == null, { typeName = null }, label = { Text(stringResource(Res.string.search_all)) })
                listOf(DocumentType.Note to Res.string.type_note, DocumentType.Recipe to Res.string.type_recipe, DocumentType.ShoppingList to Res.string.type_shopping_list).forEach { (type, label) ->
                    FilterChip(typeName == type.wire, { typeName = if (typeName == type.wire) null else type.wire }, label = { Text(stringResource(label)) })
                }
                FilterChip(archive, { archive = !archive }, label = { Text(stringResource(Res.string.search_archived)) })
                FilterChip(color != null, { if (color != null) color = null else colorDialog = true }, label = { Text(stringResource(Res.string.action_color)) })
                labels.forEach { label -> FilterChip(tag == label.tag, { tag = if (tag == label.tag) null else label.tag }, label = { Text(label.tag) }) }
            }
            error?.let { Text(stringResource(Res.string.status_error, it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (results.isEmpty()) EmptyState(painterResource(Res.drawable.ic_search), stringResource(Res.string.search_no_results), stringResource(Res.string.search_placeholder))
            else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { row ->
                    DocumentCard(row, onClick = { DocumentType.fromWire(row.type)?.let { onOpen(row.id, it) } })
                }
            }
        }
    }
    if (colorDialog) ColorDialog({ colorDialog = false }) { color = it }
}
