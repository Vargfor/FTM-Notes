package io.github.vargfor.ftmnotes.feature.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.feature.importer.PromptActions
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.*
import org.koin.compose.koinInject

@Composable
fun FoodImportScreen(onBack: () -> Unit, onPreview: (String) -> Unit) {
    val service: RecipeImportService = koinInject()
    val imports: ImportCoordinator = koinInject()
    val bridge = koinInject<AppPlatform>().bridge
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var pasted by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    fun run(block: suspend () -> Unit) {
        scope.launch { busy = true; problem = null
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = e.message }
            finally { busy = false }
        }
    }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.action_import), onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(Res.string.food_import_url), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.food_source_url)) }, supportingText = { Text(stringResource(Res.string.food_import_url_hint)) })
                Button({ run { onPreview(service.fromUrl(url.trim()).id) } }, enabled = !busy && url.isNotBlank()) { Text(stringResource(Res.string.food_import_review)) }
            }
            item { OutlinedButton({ run {
                val files = bridge.pickFiles()
                if (files.isNotEmpty()) {
                    require(files.size == 1) { "Choose one Paprika export" }
                    onPreview(service.fromPaprika(files.single()).id)
                }
            } }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(Res.string.food_import_paprika)) } }
            item {
                HorizontalDivider()
                OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text(stringResource(Res.string.food_import_paste)) })
                Button({ run { onPreview(imports.prepareFtm(pasted).id) } }, enabled = !busy && pasted.isNotBlank()) { Text(stringResource(Res.string.import_preview)) }
            }
            item { PromptActions(DocumentType.Recipe) { problem = it } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            problem?.let { item { Text(stringResource(Res.string.status_error, it), color = MaterialTheme.colorScheme.error) } }
        }
    }
}
