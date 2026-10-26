package io.github.vargfor.ftmnotes.core.settings

import io.github.vargfor.ftmnotes.core.model.Hlc
import io.github.vargfor.ftmnotes.core.model.SettingValue
import io.github.vargfor.ftmnotes.core.model.SettingsDocument
import kotlinx.serialization.json.JsonElement

/**
 * All raw setting values at one point in time. Reading a flag never fails: a missing or
 * undecodable value gives the flag's default. Raw maps may hold keys no flag knows about.
 */
data class SettingsSnapshot(
    val synced: Map<String, JsonElement> = emptyMap(),
    val device: Map<String, JsonElement> = emptyMap(),
) {
    operator fun <T> get(flag: FeatureFlag<T>): T {
        val raw = when (flag.scope) {
            FlagScope.Synced -> synced[flag.id]
            FlagScope.Device -> device[flag.id]
        } ?: return flag.default
        return flag.decode(raw) ?: flag.default
    }

    /** The tabs to show, in order, and the one the app opens on. Always at least one tab. */
    fun tabLayout(): TabLayout {
        val start = this[Flags.startTab]
        val order = (this[Flags.tabOrder].split(',').mapNotNull { wire -> SectionId.entries.firstOrNull { it.wire == wire } } + SectionId.entries).distinct()
        val visible = order.filter { this[Flags.tabVisible(it)] }.ifEmpty { listOf(start) }
        return TabLayout(visible, if (start in visible) start else visible.first())
    }

    companion object {
        val Defaults = SettingsSnapshot()
    }
}

data class TabLayout(val tabs: List<SectionId>, val start: SectionId)

/** Returns a copy with [key] set to [value], stamped with [hlc] for per-key merging. */
fun SettingsDocument.withValue(key: String, value: JsonElement, hlc: Hlc): SettingsDocument =
    copy(values = values + (key to SettingValue(value, hlc.encode())))

object SettingsMerge {
    /**
     * Per-key last-writer-wins: for each key the value with the higher HLC wins (on a tie, [local]).
     * Keys present on only one side are kept, including keys this app version doesn't know.
     */
    fun merge(local: Map<String, SettingValue>, remote: Map<String, SettingValue>): Map<String, SettingValue> {
        val merged = LinkedHashMap(local)
        for ((key, theirs) in remote) {
            val ours = merged[key]
            if (ours == null || theirs.hlc > ours.hlc) merged[key] = theirs
        }
        return merged
    }

    fun merge(local: SettingsDocument, remote: SettingsDocument): SettingsDocument =
        local.copy(values = merge(local.values, remote.values))
}
