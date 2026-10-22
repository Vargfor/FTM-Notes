package app.ftmnotes.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/** Every destination in the app. Routes are serializable so back stacks survive process death. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object TrainingHome : Route
    @Serializable data object FoodHome : Route
    @Serializable data object NotesHome : Route

    /** [noteId] may not exist yet: a brand-new note is only written once it has content. */
    @Serializable data class NoteEditor(val noteId: String) : Route

    @Serializable data object Settings : Route
    @Serializable data object Search : Route
}

/** Required on iOS/desktop, where NavKey polymorphism can't be discovered by reflection. */
@OptIn(ExperimentalSerializationApi::class)
internal val NavSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclassesOfSealed<Route>()
        }
    }
}
