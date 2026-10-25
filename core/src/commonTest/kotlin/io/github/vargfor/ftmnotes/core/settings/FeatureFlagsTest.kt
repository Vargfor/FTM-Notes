package io.github.vargfor.ftmnotes.core.settings

import io.github.vargfor.ftmnotes.core.model.Hlc
import io.github.vargfor.ftmnotes.core.model.SettingValue
import io.github.vargfor.ftmnotes.core.model.SettingsDocument
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureFlagsTest {

    @Test
    fun anEmptySnapshotGivesEveryDefault() {
        val snapshot = SettingsSnapshot.Defaults
        Flags.all.forEach { flag -> assertEquals(flag.default, snapshot[flag], flag.id) }
        assertEquals(ThemeMode.System, snapshot[Flags.theme])
        assertEquals(30, snapshot[Flags.trashRetentionDays])
    }

    @Test
    fun idsAreUniqueAndLookLikeDottedKeys() {
        val ids = Flags.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(Regex("^[a-z]+(\\.[a-z_]+)+$").matches(it), it) }
        assertEquals(Flags.pureBlack, Flags.byId("app.theme.pure_black"))
        assertNull(Flags.byId("nope"))
    }

    @Test
    fun valuesRoundTripThroughTheirEncoding() {
        val snapshot = SettingsSnapshot(
            synced = mapOf(
                Flags.startTab.id to Flags.startTab.encode(SectionId.Food),
                Flags.trashRetentionDays.id to Flags.trashRetentionDays.encode(Flags.NEVER),
                Flags.swipeToArchive.id to Flags.swipeToArchive.encode(false),
            ),
            device = mapOf(Flags.theme.id to Flags.theme.encode(ThemeMode.Dark)),
        )
        assertEquals(SectionId.Food, snapshot[Flags.startTab])
        assertEquals(Flags.NEVER, snapshot[Flags.trashRetentionDays])
        assertEquals(false, snapshot[Flags.swipeToArchive])
        assertEquals(ThemeMode.Dark, snapshot[Flags.theme])
    }

    @Test
    fun undecodableValuesFallBackToTheDefault() {
        val snapshot = SettingsSnapshot(
            synced = mapOf(
                Flags.startTab.id to JsonPrimitive("dashboard"), // an option from a newer version
                Flags.trashRetentionDays.id to JsonPrimitive(45), // not one of the choices
                Flags.swipeToArchive.id to JsonPrimitive("false"), // wrong type
                Flags.checkedSection.id to JsonNull,
                Flags.notesTab.id to JsonObject(emptyMap()),
            ),
        )
        assertEquals(SectionId.Notes, snapshot[Flags.startTab])
        assertEquals(30, snapshot[Flags.trashRetentionDays])
        assertEquals(true, snapshot[Flags.swipeToArchive])
        assertEquals(true, snapshot[Flags.checkedSection])
        assertEquals(true, snapshot[Flags.notesTab])
    }

    @Test
    fun aValueStoredInTheWrongScopeIsIgnored() {
        // Theme is per device; a synced value for it (e.g. written by a buggy build) must not apply.
        val snapshot = SettingsSnapshot(synced = mapOf(Flags.theme.id to JsonPrimitive("dark")))
        assertEquals(ThemeMode.System, snapshot[Flags.theme])
    }

    @Test
    fun unknownKeysAreIgnoredWhenReading() {
        val snapshot = SettingsSnapshot(synced = mapOf("food.future_feature" to JsonPrimitive(true)))
        Flags.all.forEach { flag -> assertEquals(flag.default, snapshot[flag], flag.id) }
    }

    // ------------------------------------------------------------ tabs

    private fun tabs(vararg values: Pair<FeatureFlag<*>, Any>): TabLayout {
        @Suppress("UNCHECKED_CAST")
        val synced = values.associate { (flag, value) -> flag.id to (flag as FeatureFlag<Any>).encode(value) }
        return SettingsSnapshot(synced = synced).tabLayout()
    }

    @Test
    fun allTabsShowByDefaultAndTheAppOpensOnNotes() {
        assertEquals(TabLayout(SectionId.entries, SectionId.Notes), tabs())
    }

    @Test
    fun hiddenTabsAreLeftOutInTheirUsualOrder() {
        assertEquals(TabLayout(listOf(SectionId.Training, SectionId.Notes), SectionId.Training), tabs(Flags.foodTab to false, Flags.startTab to SectionId.Training))
    }

    @Test
    fun aHiddenStartTabFallsBackToTheFirstVisibleOne() {
        assertEquals(TabLayout(listOf(SectionId.Training, SectionId.Food), SectionId.Training), tabs(Flags.notesTab to false))
    }

    @Test
    fun hidingEveryTabStillShowsTheStartTab() {
        val layout = tabs(Flags.trainingTab to false, Flags.foodTab to false, Flags.notesTab to false, Flags.startTab to SectionId.Food)
        assertEquals(TabLayout(listOf(SectionId.Food), SectionId.Food), layout)
    }

    // ------------------------------------------------------------ merge

    private fun hlc(millis: Long, node: String = "a") = Hlc(millis, 0, node).encode()

    @Test
    fun mergeTakesTheNewerValuePerKey() {
        val local = mapOf(
            "app.start_tab" to SettingValue(JsonPrimitive("food"), hlc(200)),
            "notes.swipe_to_archive" to SettingValue(JsonPrimitive(false), hlc(100)),
        )
        val remote = mapOf(
            "app.start_tab" to SettingValue(JsonPrimitive("training"), hlc(150, "b")),
            "notes.swipe_to_archive" to SettingValue(JsonPrimitive(true), hlc(300, "b")),
        )
        val merged = SettingsMerge.merge(local, remote)
        assertEquals(JsonPrimitive("food"), merged.getValue("app.start_tab").v)
        assertEquals(JsonPrimitive(true), merged.getValue("notes.swipe_to_archive").v)
        // Merging is order-independent: both devices end up with the same values.
        assertEquals(merged, SettingsMerge.merge(remote, local))
    }

    @Test
    fun mergeKeepsKeysFromEitherSideIncludingUnknownOnes() {
        val local = mapOf("app.tabs.food" to SettingValue(JsonPrimitive(false), hlc(1)))
        val remote = mapOf("training.future_flag" to SettingValue(JsonPrimitive(3), hlc(2, "b")))
        assertEquals(setOf("app.tabs.food", "training.future_flag"), SettingsMerge.merge(local, remote).keys)
    }

    @Test
    fun withValueStampsTheKeyAndLeavesOthersAlone() {
        val doc = SettingsDocument(values = mapOf("x.unknown" to SettingValue(JsonPrimitive(1), hlc(1))))
        val updated = doc.withValue(Flags.startTab.id, JsonPrimitive("food"), Hlc(5, 0, "a"))
        assertEquals(hlc(5), updated.values.getValue(Flags.startTab.id).hlc)
        assertEquals(doc.values.getValue("x.unknown"), updated.values.getValue("x.unknown"))
    }
}
