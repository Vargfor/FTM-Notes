package app.ftmnotes.feature.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.ftmnotes.designsystem.components.BackButton
import app.ftmnotes.resources.Res
import app.ftmnotes.resources.note_body_hint
import app.ftmnotes.resources.note_title_hint
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun NoteEditorScreen(
    noteId: String,
    onBack: () -> Unit,
    viewModel: NoteEditorViewModel = koinViewModel { parametersOf(noteId) },
) {
    val titleFocus = remember { FocusRequester() }
    // A brand-new note opens ready to type.
    LaunchedEffect(viewModel.isLoaded) {
        if (viewModel.isLoaded && viewModel.isNew) titleFocus.requestFocus()
    }

    Scaffold(
        topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        if (!viewModel.isLoaded) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            DocumentField(
                state = viewModel.title,
                placeholder = stringResource(Res.string.note_title_hint),
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                modifier = Modifier.focusRequester(titleFocus),
            )
            DocumentField(
                state = viewModel.content,
                placeholder = stringResource(Res.string.note_body_hint),
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = false,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Borderless text field that reads like a document rather than a form. */
@Composable
private fun DocumentField(
    state: TextFieldState,
    placeholder: String,
    textStyle: TextStyle,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        textStyle = textStyle.copy(color = color),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        decorator = TextFieldDecorator { innerTextField ->
            Box {
                if (state.text.isEmpty()) {
                    Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
                innerTextField()
            }
        },
    )
}
