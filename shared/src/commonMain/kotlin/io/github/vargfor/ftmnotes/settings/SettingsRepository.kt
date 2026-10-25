package io.github.vargfor.ftmnotes.settings

import io.github.vargfor.ftmnotes.core.model.SettingsDocument
import io.github.vargfor.ftmnotes.core.settings.FeatureFlag
import io.github.vargfor.ftmnotes.core.settings.FlagScope
import io.github.vargfor.ftmnotes.core.settings.SettingsSnapshot
import io.github.vargfor.ftmnotes.core.settings.withValue
import io.github.vargfor.ftmnotes.data.DeviceClock
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.db.DevicePrefDao
import io.github.vargfor.ftmnotes.data.db.DevicePrefEntity
import io.github.vargfor.ftmnotes.di.AppScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Reads and writes [FeatureFlag] values. Synced flags live in the single settings document (one
 * HLC per key, so devices can merge key by key); device flags live in `device_prefs`.
 *
 * Values are collected eagerly from the database. Platforms call [awaitLoaded] before showing the
 * first frame so the theme and start tab are right from the start (no flash of defaults).
 */
class SettingsRepository(
    private val documents: DocumentRepository,
    private val prefs: DevicePrefDao,
    private val clock: DeviceClock,
    scope: AppScope,
) {
    private val state = MutableStateFlow(SettingsSnapshot.Defaults)
    private val loaded = CompletableDeferred<Unit>()

    /** The latest values; holds defaults until the first database read completes. */
    val snapshot: StateFlow<SettingsSnapshot> = state.asStateFlow()

    init {
        scope.launch {
            combine(syncedValues(), deviceValues(), ::SettingsSnapshot).collect {
                state.value = it
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    fun <T> observe(flag: FeatureFlag<T>): Flow<T> = snapshot.map { it[flag] }.distinctUntilChanged()

    suspend fun <T> set(flag: FeatureFlag<T>, value: T) {
        val encoded = flag.encode(value)
        when (flag.scope) {
            FlagScope.Device -> prefs.put(DevicePrefEntity(flag.id, encoded.toString()))
            FlagScope.Synced -> {
                val hlc = clock.now()
                documents.updateOrCreate(
                    SettingsDocument.ID,
                    create = { SettingsDocument().withValue(flag.id, encoded, hlc) },
                ) { doc ->
                    val settings = doc as? SettingsDocument ?: return@updateOrCreate doc
                    // Re-stamping an unchanged value would make it win merges it shouldn't.
                    if (settings.values[flag.id]?.v == encoded) settings else settings.withValue(flag.id, encoded, hlc)
                }
            }
        }
    }

    private fun syncedValues(): Flow<Map<String, JsonElement>> =
        documents.observe(SettingsDocument.ID)
            .map { stored -> (stored?.document as? SettingsDocument)?.values?.mapValues { it.value.v }.orEmpty() }
            .distinctUntilChanged()

    private fun deviceValues(): Flow<Map<String, JsonElement>> =
        prefs.observeAll()
            .map { rows ->
                // device_prefs also holds non-flag values; anything that isn't JSON is simply not a flag.
                rows.mapNotNull { row -> runCatching { Json.parseToJsonElement(row.value) }.getOrNull()?.let { row.key to it } }.toMap()
            }
            .distinctUntilChanged()
}
