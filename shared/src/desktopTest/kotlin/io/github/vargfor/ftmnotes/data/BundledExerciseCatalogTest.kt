package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.resources.Res
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalResourceApi::class)
class BundledExerciseCatalogTest {
    @Test fun everySourceEntryKeepsItsNameCategoryMusclesInstructionsAndPinnedReference() = runBlocking {
        val snapshot = Json.parseToJsonElement(Res.readBytes("files/exercise_catalog.json").decodeToString()).jsonObject
        val source = snapshot["exercises"]!!.jsonArray
        val catalog = BundledExerciseCatalog.load()
        assertEquals(876, source.size)
        assertEquals(909, catalog.size)
        assertEquals(909, catalog.map { it.id }.toSet().size)
        assertEquals(7, catalog.map { it.category }.toSet().size)
        assertTrue(catalog.all { it.category.isNotBlank() && TrainingValidation.issues(it).isEmpty() })
        val bySource = catalog.filter { it.instructionUrl != null }.associateBy { it.instructionUrl!!.substringAfterLast('/').removeSuffix(".json") }
        for (raw in source) {
            val row = raw.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val exercise = bySource[row["id"]!!.jsonPrimitive.content] ?: error(name)
            assertTrue(name == exercise.title || name in exercise.aliases, name)
            assertEquals(row["category"]!!.jsonPrimitive.content, exercise.category, name)
            assertEquals(row["primaryMuscles"]!!.jsonArray.map { it.jsonPrimitive.content }, exercise.muscleGroups, name)
            row["instructions"]!!.jsonArray.forEach { assertTrue(it.jsonPrimitive.content in exercise.notes, name) }
            assertTrue(snapshot["revision"]!!.jsonPrimitive.content in exercise.instructionUrl!!, name)
        }
        assertEquals(catalog, BundledExerciseCatalog.load())
        assertTrue(ExerciseCatalog.all.all { old -> catalog.any { it.id == old.id } })
    }

    @Test fun upgradingAnUntouchedSeedAddsMetadataAndKeepsItsIdentityAndTimestamps() = runBlocking {
        val old = ExerciseCatalog.all.first().copy(createdAt = Instant.parse("2026-01-01T00:00:00Z"), updatedAt = Instant.parse("2026-01-02T00:00:00Z"))
        val bundled = BundledExerciseCatalog.load().single { it.id == old.id }
        val updated = BundledExerciseCatalog.upgrade(old, bundled)
        assertEquals(old.id, updated.id); assertEquals(old.createdAt, updated.createdAt)
        assertEquals(old.updatedAt, updated.updatedAt)
        assertEquals("strength", updated.category); assertTrue(updated.notes.isNotBlank())
    }

    @Test fun libraryUpdatesPreservePersonalEditsAndArchivedExercises() = runBlocking {
        val old = ExerciseCatalog.all.first()
        val bundled = BundledExerciseCatalog.load().single { it.id == old.id }
        for (edited in listOf(old.copy(notes = "My own technique notes"), old.copy(archived = true),
            old.copy(defaultRestSeconds = 120), old.copy(title = "My bench"), old.copy(category = "My category")))
            assertEquals(edited, BundledExerciseCatalog.upgrade(edited, bundled))
    }

    @Test fun originalEncodingMistakesDoNotPreventUneditedSeedsFromReceivingCategories() = runBlocking {
        val catalog = BundledExerciseCatalog.load()
        for ((title, alias) in listOf("Overhead Press" to "Milit?rpress", "Deadlift" to "Markl?ft")) {
            val old = ExerciseCatalog.all.single { it.title == title }.copy(aliases = listOf(alias))
            val bundled = catalog.single { it.id == old.id }
            val updated = BundledExerciseCatalog.upgrade(old, bundled)
            assertEquals("strength", updated.category)
            assertTrue(updated.notes.isNotBlank())
            assertFalse(updated.aliases.any { '?' in it })
        }
    }
}
