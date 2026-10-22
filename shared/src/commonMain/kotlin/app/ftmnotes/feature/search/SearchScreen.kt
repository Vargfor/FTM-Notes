package app.ftmnotes.feature.search

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.ftmnotes.designsystem.components.BackTopBar
import app.ftmnotes.designsystem.components.ComingSoonLabel
import app.ftmnotes.designsystem.components.EmptyState
import app.ftmnotes.resources.Res
import app.ftmnotes.resources.ic_search
import app.ftmnotes.resources.search_placeholder
import app.ftmnotes.resources.search_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SearchScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.search_title), onBack) }) { padding ->
        EmptyState(
            icon = painterResource(Res.drawable.ic_search),
            title = stringResource(Res.string.search_title),
            body = stringResource(Res.string.search_placeholder),
            modifier = Modifier.padding(padding),
            action = { ComingSoonLabel() },
        )
    }
}
