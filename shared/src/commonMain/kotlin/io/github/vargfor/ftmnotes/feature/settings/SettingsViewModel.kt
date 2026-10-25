package io.github.vargfor.ftmnotes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vargfor.ftmnotes.core.settings.FeatureFlag
import io.github.vargfor.ftmnotes.core.settings.SettingsSnapshot
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val snapshot: StateFlow<SettingsSnapshot> = settings.snapshot

    fun <T> set(flag: FeatureFlag<T>, value: T) {
        viewModelScope.launch { settings.set(flag, value) }
    }
}
