package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.training.ExerciseCatalog
import io.github.vargfor.ftmnotes.resources.Res
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Text only, loaded locally. No service, account, image downloads or runtime catalog dependency. */
object BundledExerciseCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Snapshot(val repository: String, val revision: String, val exercises: List<Entry>)
    @Serializable private data class Entry(
        val id: String, val name: String, val category: String, val equipment: String?,
        val primaryMuscles: List<String>, val instructions: List<String>, val legacySlug: String? = null,
    )

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): List<ExerciseDocument> {
        val snapshot = json.decodeFromString<Snapshot>(Res.readBytes("files/exercise_catalog.json").decodeToString())
        val legacy = ExerciseCatalog.all.associateBy { it.id }
        val sourced = snapshot.exercises.map { entry ->
            val old = entry.legacySlug?.let { legacy[Ids.seeded("exercise", it)] }
            val equipment = when (entry.equipment) {
                "body only" -> "bodyweight"; "kettlebells" -> "kettlebell"; "bands" -> "band"
                else -> entry.equipment.orEmpty()
            }
            val tracking = old?.tracking ?: when {
                entry.category == "stretching" || entry.id == "Rope_Jumping" || entry.id == "Stairmaster" || entry.id == "Step_Mill" -> TrackingMode.Duration
                entry.category == "cardio" -> TrackingMode.DurationDistance
                equipment == "bodyweight" -> TrackingMode.BodyweightReps
                equipment in listOf("barbell", "dumbbell", "kettlebell", "cable", "machine", "e-z curl bar") -> TrackingMode.WeightReps
                else -> TrackingMode.Reps
            }
            (old ?: ExerciseDocument(id = Ids.seeded("free-exercise-db", entry.id), title = entry.name)).copy(
                aliases = (old?.aliases.orEmpty() + listOfNotNull(entry.name.takeIf { old != null && it != old.title })).distinct(),
                category = entry.category, muscleGroups = entry.primaryMuscles, equipment = equipment,
                tracking = tracking, increment = old?.increment ?: if (equipment == "dumbbell") 1.0 else 2.5,
                notes = entry.instructions.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n\n"),
                instructionUrl = "${snapshot.repository}/blob/${snapshot.revision}/exercises/${entry.id}.json",
            )
        }
        val used = sourced.map { it.id }.toSet()
        return (sourced + legacy.values.filter { it.id !in used }.map { old ->
            old.copy(category = if (old.tracking == TrackingMode.DurationDistance) "cardio" else "strength")
        }).sortedBy { it.title.lowercase() }
    }

    /** Supply new metadata only when an existing seed is still unchanged. Preserve edits and trash. */
    fun upgrade(existing: ExerciseDocument, bundled: ExerciseDocument): ExerciseDocument {
        val original = ExerciseCatalog.all.firstOrNull { it.id == existing.id } ?: return existing
        // The first seed version accidentally stored '?' in these two Norwegian aliases.
        val comparable = existing.copy(id = null, createdAt = null, updatedAt = null,
            aliases = existing.aliases.map { when (it) {
                "Milit?rpress" -> "Militærpress"; "Markl?ft" -> "Markløft"; else -> it
            } })
        return if (comparable == original.copy(id = null))
            bundled.copy(createdAt = existing.createdAt, updatedAt = existing.updatedAt)
        else existing
    }
}
