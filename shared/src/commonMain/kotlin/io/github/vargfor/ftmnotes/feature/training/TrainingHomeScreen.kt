package io.github.vargfor.ftmnotes.feature.training

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.github.vargfor.ftmnotes.designsystem.components.ComingSoonLabel
import io.github.vargfor.ftmnotes.designsystem.components.EmptyState
import io.github.vargfor.ftmnotes.designsystem.components.SectionTopBar
import io.github.vargfor.ftmnotes.resources.Res
import io.github.vargfor.ftmnotes.resources.ic_fitness_center
import io.github.vargfor.ftmnotes.resources.tab_training
import io.github.vargfor.ftmnotes.resources.training_empty_body
import io.github.vargfor.ftmnotes.resources.training_empty_title
import io.github.vargfor.ftmnotes.resources.training_subtitle
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun TrainingHomeScreen(onOpenSearch: () -> Unit, onOpenSettings: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SectionTopBar(
                title = stringResource(Res.string.tab_training),
                subtitle = stringResource(Res.string.training_subtitle),
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        EmptyState(
            icon = painterResource(Res.drawable.ic_fitness_center),
            title = stringResource(Res.string.training_empty_title),
            body = stringResource(Res.string.training_empty_body),
            modifier = Modifier.padding(padding),
            action = { ComingSoonLabel() },
        )
    }
}
