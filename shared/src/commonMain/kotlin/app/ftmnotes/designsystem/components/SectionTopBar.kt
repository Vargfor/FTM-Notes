package app.ftmnotes.designsystem.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import app.ftmnotes.resources.Res
import app.ftmnotes.resources.action_back
import app.ftmnotes.resources.action_search
import app.ftmnotes.resources.action_settings
import app.ftmnotes.resources.ic_arrow_back
import app.ftmnotes.resources.ic_search
import app.ftmnotes.resources.ic_settings
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Large collapsing header used at the root of each section, with global search and settings. */
@Composable
fun SectionTopBar(
    title: String,
    subtitle: String,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeFlexibleTopAppBar(
        title = { Text(title) },
        subtitle = { Text(subtitle) },
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(painterResource(Res.drawable.ic_search), contentDescription = stringResource(Res.string.action_search))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(Res.drawable.ic_settings), contentDescription = stringResource(Res.string.action_settings))
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/** Standard header for pushed screens: title plus a back arrow. */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { BackButton(onBack) },
        actions = { actions() },
    )
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = stringResource(Res.string.action_back))
    }
}
