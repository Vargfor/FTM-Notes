package app.ftmnotes.feature.food

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.ftmnotes.designsystem.components.ComingSoonLabel
import app.ftmnotes.designsystem.components.EmptyState
import app.ftmnotes.designsystem.components.SectionTopBar
import app.ftmnotes.resources.Res
import app.ftmnotes.resources.food_empty_body
import app.ftmnotes.resources.food_empty_title
import app.ftmnotes.resources.food_subtitle
import app.ftmnotes.resources.ic_skillet
import app.ftmnotes.resources.tab_food
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun FoodHomeScreen(onOpenSearch: () -> Unit, onOpenSettings: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SectionTopBar(
                title = stringResource(Res.string.tab_food),
                subtitle = stringResource(Res.string.food_subtitle),
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        EmptyState(
            icon = painterResource(Res.drawable.ic_skillet),
            title = stringResource(Res.string.food_empty_title),
            body = stringResource(Res.string.food_empty_body),
            modifier = Modifier.padding(padding),
            action = { ComingSoonLabel() },
        )
    }
}
