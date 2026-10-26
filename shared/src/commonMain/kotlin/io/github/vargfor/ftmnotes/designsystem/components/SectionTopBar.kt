package io.github.vargfor.ftmnotes.designsystem.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import io.github.vargfor.ftmnotes.resources.Res
import io.github.vargfor.ftmnotes.resources.action_back
import io.github.vargfor.ftmnotes.resources.action_search
import io.github.vargfor.ftmnotes.resources.action_settings
import io.github.vargfor.ftmnotes.resources.ic_arrow_back
import io.github.vargfor.ftmnotes.resources.ic_search
import io.github.vargfor.ftmnotes.resources.ic_settings
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
    extraActions: @Composable () -> Unit = {},
) {
    LargeFlexibleTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        subtitle = { Text(subtitle) },
        actions = {
            extraActions()
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
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
