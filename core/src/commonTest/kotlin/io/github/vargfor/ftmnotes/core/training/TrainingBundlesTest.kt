package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.format.FtmReader
import kotlin.test.*

class TrainingBundlesTest {
    @Test fun nameAndAliasesResolveWhileMissingExercisesAreCreated() {
        val bench = ExerciseDocument("bench", "Barbell Bench Press", aliases = listOf("Bench"))
        val routine = WorkoutTemplateDocument("routine", "Upper", blocks = listOf(WorkoutBlock(exercise = ExerciseRef(name = "Bench")), WorkoutBlock(exercise = ExerciseRef(name = "New lift"))))
        val result = TrainingBundles.resolve(listOf(routine), listOf(bench))
        assertTrue(result.questions.isEmpty())
        val blocks = result.documents.filterIsInstance<WorkoutTemplateDocument>().single().blocks
        assertEquals("bench", blocks[0].exercise.id)
        assertEquals(blocks[1].exercise.id, result.documents.filterIsInstance<ExerciseDocument>().single().id)
    }
    @Test fun ambiguousNamesRequireAChoiceAndCollisionIdsRemainStableDuringReview() {
        val existing = listOf(ExerciseDocument("barbell", "Press", equipment = "barbell"), ExerciseDocument("dumbbell", "Press", equipment = "dumbbell"), NoteDocument("routine", "Existing note"))
        val routine = WorkoutTemplateDocument("routine", "Upper", blocks = listOf(WorkoutBlock(exercise = ExerciseRef(name = "Press"))))
        val first = TrainingBundles.resolve(listOf(routine), existing, namespace = "batch")
        val question = first.questions.single()
        val selected = TrainingBundles.resolve(listOf(routine), existing, mapOf(question.key to "dumbbell"), namespace = "batch")
        assertTrue(selected.questions.isEmpty())
        assertEquals(question.ownerId, selected.documents.filterIsInstance<WorkoutTemplateDocument>().single().id)
        assertEquals("dumbbell", selected.documents.filterIsInstance<WorkoutTemplateDocument>().single().blocks.single().exercise.id)
        val created = TrainingBundles.resolve(listOf(routine), existing, mapOf(question.key to "@new"), namespace = "batch")
        assertEquals(1, created.documents.filterIsInstance<ExerciseDocument>().size)
    }
    @Test fun collidingBundleIdsAreRemappedThroughEveryLinkedDocumentAndClosure() {
        val exercise = ExerciseDocument("exercise", "Custom lift")
        val routine = WorkoutTemplateDocument("routine", "Routine", blocks = listOf(WorkoutBlock(exercise = ExerciseRef("exercise", "Custom lift"))))
        val plan = TrainingPlanDocument("plan", "Program", days = listOf(PlanDay("day", "A", TemplateRef("routine", "Routine"))),
            exerciseRules = listOf(ExerciseProgression(ExerciseRef("exercise", "Custom lift"))))
        val result = TrainingBundles.resolve(listOf(plan, routine, exercise), listOf(NoteDocument("exercise"), NoteDocument("routine"), NoteDocument("plan")), namespace = "batch")
        val p = result.documents.filterIsInstance<TrainingPlanDocument>().single()
        val r = result.documents.filterIsInstance<WorkoutTemplateDocument>().single()
        val e = result.documents.filterIsInstance<ExerciseDocument>().single()
        assertNotEquals("plan", p.id); assertEquals(r.id, p.days.single().template.id); assertEquals(e.id, r.blocks.single().exercise.id); assertEquals(e.id, p.exerciseRules.single().exercise.id)
        assertEquals(3, TrainingBundles.closure(setOf(p.id!!), result.documents).size)
        assertTrue(TrainingBundles.validateReferences(result.documents, emptyList()).isEmpty())
    }
    @Test fun duplicateHistoryAndLiveImportsAreNotSilentlyAccepted() {
        val duplicate = TrainingBundles.resolve(listOf(NoteDocument("same"), NoteDocument("same")), emptyList())
        assertTrue(duplicate.issues.any { it.path == "$" })
        val draft = WorkoutSessionDocument("draft")
        assertTrue(TrainingBundles.validateReferences(listOf(draft), emptyList()).isNotEmpty())
        val missing = WorkoutTemplateDocument("r", blocks = listOf(WorkoutBlock(exercise = ExerciseRef("missing"))))
        assertTrue(TrainingBundles.validateReferences(listOf(missing), emptyList()).isNotEmpty())
    }
    @Test fun strongCsvHandlesQuotedNamesUnitsDurationsAndRowProblems() {
        val csv = "Date;Workout Name;Exercise Name;Set Order;Weight (lbs);Reps;Distance;Seconds;Notes\n" +
            "\"2026-10-20 10:00:00\";\"Upper; A\";Bench Press;1;135;8;;;\"steady; clean\"\n" +
            "2026-10-20 10:00:00;\"Upper; A\";Run;1;;;2;600;\n" +
            "bad-date;Broken;Bench Press;1;100;8;;;"
        val parsed = StrongCsv.parse(csv, MassUnit.Kg, 1000.0)
        val session = parsed.documents.filterIsInstance<WorkoutSessionDocument>().single()
        assertEquals("Upper; A", session.title)
        assertEquals(MassUnit.Lb, session.exercises.first().sets.single().unit)
        assertEquals(2000.0, session.exercises[1].sets.single().metres)
        assertEquals(600.0, session.exercises[1].sets.single().seconds)
        assertTrue(parsed.issues.any { it.path.contains("4") })
        assertEquals(session, FtmReader.read(io.github.vargfor.ftmnotes.core.format.FtmFormat.write(listOf(session))).documents.single())
    }
    @Test fun strongDistanceUnitHeadersUseMetresAndMilesWithoutAUnitPromptGuess() {
        for ((unit, expected) in listOf("m" to 2.0, "km" to 2000.0, "mi" to 3218.688)) {
            val csv = "Date,Workout Name,Exercise Name,Distance (" + unit + "),Seconds\n2026-10-20 10:00:00,Run,Run,2,600"
            val parsed = StrongCsv.parse(csv, distanceMultiplier = 99.0)
            assertEquals(expected, parsed.documents.filterIsInstance<WorkoutSessionDocument>().single().exercises.single().sets.single().metres)
        }
    }

}
