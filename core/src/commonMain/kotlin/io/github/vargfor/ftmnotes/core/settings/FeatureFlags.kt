package io.github.vargfor.ftmnotes.core.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Where a flag's value lives. */
enum class FlagScope {
    /** In the settings document: follows the user to every device (merged per key by HLC). */
    Synced,

    /** In `device_prefs`: this device only (screen-dependent things like theme or layout). */
    Device,
}

/** An enum stored by a stable wire name instead of its Kotlin name, so renaming code never breaks data. */
interface WireValue {
    val wire: String
}

/**
 * A typed setting or optional behaviour. [id] is what gets stored — never change an existing one.
 * Values that can't be decoded (wrong type, an option this version doesn't know) read as [default].
 */
sealed class FeatureFlag<T>(val id: String, val default: T, val scope: FlagScope) {
    abstract fun encode(value: T): JsonElement
    abstract fun decode(json: JsonElement): T?

    class Bool(id: String, default: Boolean, scope: FlagScope) : FeatureFlag<Boolean>(id, default, scope) {
        override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
        override fun decode(json: JsonElement): Boolean? =
            (json as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }

    class Choice<E>(
        id: String,
        default: E,
        scope: FlagScope,
        val options: List<E>,
    ) : FeatureFlag<E>(id, default, scope) where E : Enum<E>, E : WireValue {
        init {
            require(default in options) { "Default of $id must be one of its options" }
        }

        override fun encode(value: E): JsonElement = JsonPrimitive(value.wire)
        override fun decode(json: JsonElement): E? {
            val wire = (json as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return options.firstOrNull { it.wire == wire }
        }
    }

    class IntChoice(
        id: String,
        default: Int,
        scope: FlagScope,
        val options: List<Int>,
    ) : FeatureFlag<Int>(id, default, scope) {
        init {
            require(default in options) { "Default of $id must be one of its options" }
        }

        override fun encode(value: Int): JsonElement = JsonPrimitive(value)
        override fun decode(json: JsonElement): Int? =
            (json as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in options }
    }
}

enum class ThemeMode(override val wire: String) : WireValue {
    System("system"),
    Light("light"),
    Dark("dark"),
}

/** The app's top-level sections, as stored in settings. */
enum class SectionId(override val wire: String) : WireValue {
    Training("training"),
    Food("food"),
    Notes("notes"),
}

enum class NotesLayout(override val wire: String) : WireValue {
    Grid("grid"),
    List("list"),
}

/** Every flag the app knows. Unknown keys found in storage (e.g. from a newer version) are kept, not dropped. */
object Flags {
    // Appearance: per device, since a phone and a tablet may want different looks.
    val theme = FeatureFlag.Choice("app.theme", ThemeMode.System, FlagScope.Device, ThemeMode.entries)
    val pureBlack = FeatureFlag.Bool("app.theme.pure_black", false, FlagScope.Device)

    // Sections
    val trainingTab = FeatureFlag.Bool("app.tabs.training", true, FlagScope.Synced)
    val foodTab = FeatureFlag.Bool("app.tabs.food", true, FlagScope.Synced)
    val notesTab = FeatureFlag.Bool("app.tabs.notes", true, FlagScope.Synced)
    val startTab = FeatureFlag.Choice("app.start_tab", SectionId.Notes, FlagScope.Synced, SectionId.entries)

    // Notes
    val notesLayout = FeatureFlag.Choice("notes.layout", NotesLayout.Grid, FlagScope.Device, NotesLayout.entries)
    val checkedSection = FeatureFlag.Bool("notes.checklist.checked_section", true, FlagScope.Synced)
    val swipeToArchive = FeatureFlag.Bool("notes.swipe_to_archive", true, FlagScope.Synced)

    /** Days a note stays in the trash before it's erased; [NEVER] keeps it until emptied by hand. */
    val trashRetentionDays = FeatureFlag.IntChoice("notes.trash.retention_days", 30, FlagScope.Synced, listOf(7, 30, 90, NEVER))
    const val NEVER = 0

    fun tabVisible(section: SectionId): FeatureFlag.Bool = when (section) {
        SectionId.Training -> trainingTab
        SectionId.Food -> foodTab
        SectionId.Notes -> notesTab
    }

    val all: List<FeatureFlag<*>> = listOf(
        theme, pureBlack, trainingTab, foodTab, notesTab, startTab,
        notesLayout, checkedSection, swipeToArchive, trashRetentionDays,
    )

    fun byId(id: String): FeatureFlag<*>? = all.firstOrNull { it.id == id }
}
