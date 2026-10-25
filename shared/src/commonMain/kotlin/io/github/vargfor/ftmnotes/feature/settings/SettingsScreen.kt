package io.github.vargfor.ftmnotes.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.core.settings.SectionId
import io.github.vargfor.ftmnotes.core.settings.ThemeMode
import io.github.vargfor.ftmnotes.designsystem.components.BackTopBar
import io.github.vargfor.ftmnotes.resources.Res
import io.github.vargfor.ftmnotes.resources.settings_appearance
import io.github.vargfor.ftmnotes.resources.settings_pure_black
import io.github.vargfor.ftmnotes.resources.settings_pure_black_summary
import io.github.vargfor.ftmnotes.resources.settings_sections
import io.github.vargfor.ftmnotes.resources.settings_sections_summary
import io.github.vargfor.ftmnotes.resources.settings_start_tab
import io.github.vargfor.ftmnotes.resources.settings_theme
import io.github.vargfor.ftmnotes.resources.settings_theme_dark
import io.github.vargfor.ftmnotes.resources.settings_theme_light
import io.github.vargfor.ftmnotes.resources.settings_theme_system
import io.github.vargfor.ftmnotes.resources.settings_title
import io.github.vargfor.ftmnotes.resources.tab_food
import io.github.vargfor.ftmnotes.resources.tab_notes
import io.github.vargfor.ftmnotes.resources.tab_training
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Settings v1: appearance and sections. Notes, backup and the rest arrive with their features. */
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val settings by viewModel.snapshot.collectAsStateWithLifecycle()
    val tabs = settings.tabLayout()

    Scaffold(topBar = { BackTopBar(stringResource(Res.string.settings_title), onBack) }) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item { SectionHeader(stringResource(Res.string.settings_appearance)) }
            item {
                ChoiceRow(
                    label = stringResource(Res.string.settings_theme),
                    options = ThemeMode.entries,
                    selected = settings[Flags.theme],
                    optionLabel = { stringResource(it.label) },
                    onSelect = { viewModel.set(Flags.theme, it) },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(Res.string.settings_pure_black),
                    summary = stringResource(Res.string.settings_pure_black_summary),
                    checked = settings[Flags.pureBlack],
                    onCheckedChange = { viewModel.set(Flags.pureBlack, it) },
                )
            }

            item { SectionHeader(stringResource(Res.string.settings_sections), stringResource(Res.string.settings_sections_summary)) }
            SectionId.entries.forEach { section ->
                item(key = section.wire) {
                    val visible = section in tabs.tabs
                    SwitchRow(
                        title = stringResource(section.label),
                        checked = visible,
                        // The last visible section can't be hidden: the app needs somewhere to open.
                        enabled = !visible || tabs.tabs.size > 1,
                        onCheckedChange = { viewModel.set(Flags.tabVisible(section), it) },
                    )
                }
            }
            if (tabs.tabs.size > 1) {
                item {
                    ChoiceRow(
                        label = stringResource(Res.string.settings_start_tab),
                        options = tabs.tabs,
                        selected = tabs.start,
                        optionLabel = { stringResource(it.label) },
                        onSelect = { viewModel.set(Flags.startTab, it) },
                    )
                }
            }
        }
    }
}

private val ThemeMode.label: StringResource
    get() = when (this) {
        ThemeMode.System -> Res.string.settings_theme_system
        ThemeMode.Light -> Res.string.settings_theme_light
        ThemeMode.Dark -> Res.string.settings_theme_dark
    }

private val SectionId.label: StringResource
    get() = when (this) {
        SectionId.Training -> Res.string.tab_training
        SectionId.Food -> Res.string.tab_food
        SectionId.Notes -> Res.string.tab_notes
    }

@Composable
private fun SectionHeader(title: String, summary: String? = null) {
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall) },
        supportingContent = summary?.let { { Text(it) } },
        modifier = Modifier.padding(top = 8.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(optionLabel(option)) }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
