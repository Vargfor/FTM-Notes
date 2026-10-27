package io.github.vargfor.ftmnotes.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.designsystem.components.*
import io.github.vargfor.ftmnotes.resources.*
import org.jetbrains.compose.resources.*
import org.koin.compose.viewmodel.koinViewModel

private data class SettingsEntry(val label: String, val content: @Composable () -> Unit)

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenImport: () -> Unit = {}, onOpenBackups: () -> Unit = {}, onOpenFoodSettings: () -> Unit = {}, onOpenTrainingSettings: () -> Unit = {}, viewModel: SettingsViewModel = koinViewModel()) {
    val settings by viewModel.snapshot.collectAsStateWithLifecycle()
    val tabs = settings.tabLayout()
    var query by rememberSaveable { mutableStateOf("") }
    val appearance = stringResource(Res.string.settings_appearance)
    val notes = stringResource(Res.string.settings_notes)
    val sections = stringResource(Res.string.settings_sections)
    val entries = mutableListOf<SettingsEntry>()
    entries += SettingsEntry(appearance + " " + stringResource(Res.string.settings_theme)) {
        ChoiceRow(stringResource(Res.string.settings_theme), ThemeMode.entries, settings[Flags.theme],
            { stringResource(it.label) }, { viewModel.set(Flags.theme, it) })
    }
    entries += SettingsEntry(appearance + " " + stringResource(Res.string.settings_help_icons)) {
        SwitchRow(stringResource(Res.string.settings_help_icons), settings[Flags.helpIcons], { viewModel.set(Flags.helpIcons, it) })
    }
    entries += SettingsEntry(appearance + " " + stringResource(Res.string.settings_pure_black)) {
        SwitchRow(stringResource(Res.string.settings_pure_black), settings[Flags.pureBlack], { viewModel.set(Flags.pureBlack, it) },
            stringResource(Res.string.settings_pure_black_summary))
    }
    val order = (settings[Flags.tabOrder].split(',').mapNotNull { wire -> SectionId.entries.firstOrNull { it.wire == wire } } + SectionId.entries).distinct()
    order.forEachIndexed { index, section ->
        entries += SettingsEntry(sections + " " + stringResource(section.label)) {
            val visible = section in tabs.tabs
            SwitchRow(stringResource(section.label), visible, { viewModel.set(Flags.tabVisible(section), it) }, enabled = !visible || tabs.tabs.size > 1)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton({
                    val next = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                    viewModel.set(Flags.tabOrder, next.joinToString(",") { it.wire })
                }, enabled = index > 0) { Text(stringResource(Res.string.action_move_up)) }
                TextButton({
                    val next = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                    viewModel.set(Flags.tabOrder, next.joinToString(",") { it.wire })
                }, enabled = index < order.lastIndex) { Text(stringResource(Res.string.action_move_down)) }
            }
        }
    }
    entries += SettingsEntry(sections + " " + stringResource(Res.string.settings_start_tab)) {
        ChoiceRow(stringResource(Res.string.settings_start_tab), tabs.tabs, tabs.start,
            { stringResource(it.label) }, { viewModel.set(Flags.startTab, it) })
    }
    entries += SettingsEntry(notes + " " + stringResource(Res.string.settings_layout)) {
        ChoiceRow(stringResource(Res.string.settings_layout), NotesLayout.entries, settings[Flags.notesLayout],
            { stringResource(if (it == NotesLayout.Grid) Res.string.notes_grid else Res.string.notes_list) }, { viewModel.set(Flags.notesLayout, it) })
    }
    entries += SettingsEntry(notes + " " + stringResource(Res.string.settings_checked_section)) {
        SwitchRow(stringResource(Res.string.settings_checked_section), settings[Flags.checkedSection], { viewModel.set(Flags.checkedSection, it) }, help = Res.string.help_checked_section)
    }
    entries += SettingsEntry(notes + " " + stringResource(Res.string.settings_swipe_archive)) {
        SwitchRow(stringResource(Res.string.settings_swipe_archive), settings[Flags.swipeToArchive], { viewModel.set(Flags.swipeToArchive, it) })
    }
    entries += SettingsEntry(notes + " " + stringResource(Res.string.settings_trash_retention)) {
        ChoiceRow(stringResource(Res.string.settings_trash_retention), Flags.trashRetentionDays.options, settings[Flags.trashRetentionDays], { if (it == Flags.NEVER) stringResource(Res.string.backup_never) else stringResource(Res.string.settings_days, it) }, help = Res.string.help_trash_retention, onSelect = { viewModel.set(Flags.trashRetentionDays, it) })
    }
    entries += SettingsEntry(stringResource(Res.string.action_import)) {
        ListItem(headlineContent = { Text(stringResource(Res.string.action_import)) },
            trailingContent = { TextButton(onOpenImport) { Text(stringResource(Res.string.action_import)) } })
    }
    entries += SettingsEntry(stringResource(Res.string.training_settings)) {
        ListItem(headlineContent = { Text(stringResource(Res.string.training_settings)) },
            trailingContent = { TextButton(onOpenTrainingSettings) { Text(stringResource(Res.string.action_settings)) } })
    }
    entries += SettingsEntry(stringResource(Res.string.food_settings)) {
        ListItem(headlineContent = { Text(stringResource(Res.string.food_settings)) },
            trailingContent = { TextButton(onOpenFoodSettings) { Text(stringResource(Res.string.action_settings)) } })
    }
    entries += SettingsEntry(stringResource(Res.string.backup_title)) {
        ListItem(headlineContent = { Text(stringResource(Res.string.backup_title)) },
            trailingContent = { TextButton(onOpenBackups) { Text(stringResource(Res.string.action_backups)) } })
    }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.settings_title), onBack) }) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(Res.drawable.ftm_logo), stringResource(Res.string.app_name), Modifier.size(64.dp))
                    Column {
                        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(Res.string.app_description), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), label = { Text(stringResource(Res.string.settings_search)) }, singleLine = true) }
            items(entries.filter { it.label.contains(query, ignoreCase = true) }, key = { it.label }) { row ->
                Text(row.label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                row.content()
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private val ThemeMode.label: StringResource get() = when (this) {
    ThemeMode.System -> Res.string.settings_theme_system
    ThemeMode.Light -> Res.string.settings_theme_light
    ThemeMode.Dark -> Res.string.settings_theme_dark
}
private val SectionId.label: StringResource get() = when (this) {
    SectionId.Training -> Res.string.tab_training
    SectionId.Food -> Res.string.tab_food
    SectionId.Notes -> Res.string.tab_notes
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, summary: String? = null, enabled: Boolean = true, help: StringResource? = null) {
    ListItem(headlineContent = { HelpLabel(title, help) }, supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked, null, enabled = enabled) },
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
internal fun <T> ChoiceRow(label: String, options: List<T>, selected: T, optionLabel: @Composable (T) -> String, onSelect: (T) -> Unit) {
    ChoiceRow(label, options, selected, optionLabel, help = null, onSelect = onSelect)
}

@Composable
internal fun <T> ChoiceRow(label: String, options: List<T>, selected: T, optionLabel: @Composable (T) -> String, help: StringResource?, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HelpLabel(label, help, style = MaterialTheme.typography.bodyLarge)
        val labels = options.map { optionLabel(it) }
        if (options.size <= 3 && labels.sumOf { it.length } <= 28 && LocalDensity.current.fontScale < 1.5f) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, item ->
                    SegmentedButton(selected == item, { onSelect(item) }, shape = SegmentedButtonDefaults.itemShape(index, options.size)) { Text(labels[index]) }
                }
            }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { index, item -> FilterChip(selected == item, { onSelect(item) }, label = { Text(labels[index]) }) }
        }
    }
}
