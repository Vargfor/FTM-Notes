package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.model.*
import kotlin.test.*
import kotlin.time.Instant

class TrainingExportTest {
    @Test fun purgedCatalogAndOptionalProgramLinksDoNotPreventHistoryExport() {
        val session = WorkoutSessionDocument("history", status = SessionStatus.Finished,
            startedAt = Instant.parse("2026-10-01T10:00:00Z"), endedAt = Instant.parse("2026-10-01T11:00:00Z"),
            templateId = "purged-routine", planId = "purged-plan", planDayId = "day",
            exercises = listOf(SessionExercise(exercise = ExerciseRef("purged-exercise", "Lift"),
                snapshot = ExerciseSnapshot("Original lift", TrackingMode.Duration, fields = listOf(CustomTrainingField("pace"))),
                sets = listOf(PerformedSet(seconds = 60.0, completed = true)))))
        val result = TrainingBundles.exportClosure(setOf("history"), listOf(session))
        val exported = result.filterIsInstance<WorkoutSessionDocument>().single()
        assertNull(exported.templateId); assertNull(exported.planId); assertNull(exported.planDayId)
        val exercise = result.filterIsInstance<ExerciseDocument>().single()
        assertEquals("Original lift", exercise.title); assertEquals(TrackingMode.Duration, exercise.tracking)
        assertEquals("pace", exercise.fields.single().key)
        assertTrue(TrainingBundles.validateReferences(result, result).isEmpty())
    }
    @Test fun wrongDocumentTypesCannotSatisfyReferences() {
        val routine = WorkoutTemplateDocument("routine", blocks = listOf(WorkoutBlock(exercise = ExerciseRef("note", "Invalid"))))
        assertTrue(TrainingBundles.validateReferences(listOf(routine), listOf(NoteDocument("note"))).isNotEmpty())
    }
}
