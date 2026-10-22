package app.ftmnotes.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.ftmnotes.designsystem.components.BackTopBar
import app.ftmnotes.designsystem.components.ComingSoonLabel
import app.ftmnotes.designsystem.components.EmptyState
import app.ftmnotes.resources.Res
import app.ftmnotes.resources.ic_settings
import app.ftmnotes.resources.settings_placeholder
import app.ftmnotes.resources.settings_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.settings_title), onBack) }) { padding ->
        EmptyState(
            icon = painterResource(Res.drawable.ic_settings),
            title = stringResource(Res.string.settings_title),
            body = stringResource(Res.string.settings_placeholder),
            modifier = Modifier.padding(padding),
            action = { ComingSoonLabel() },
        )
    }
}
