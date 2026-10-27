package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.format.*
import kotlin.test.*
import kotlin.time.Instant

class TrainingEngineTest {
    private val exercise = ExerciseDocument(id = "bench", title = "Bench press", aliases = listOf("Bench"), equipment = "barbell")
    private val template = WorkoutTemplateDocument(id = "routine", title = "Upper",
        blocks = listOf(WorkoutBlock(exercise = ExerciseRef("bench", "Bench"), sets = List(3) { SetTarget(reps = 8, load = LoadTarget(60.0)) })))
    private fun finished(reps: Int = 8, weight: Double = 60.0, completed: Boolean = true) =
        TrainingEngine.buildSession(template, listOf(exercise), prefill = false).copy(status = SessionStatus.Finished,
            startedAt = Instant.parse("2026-10-20T10:00:00Z"), endedAt = Instant.parse("2026-10-20T11:00:00Z"), planId = "plan", planWeek = 1, planDayId = "day").let { session ->
                session.copy(exercises = session.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(reps = reps, weight = weight, completed = completed) }) })
            }
    private val plan = TrainingPlanDocument(id = "plan", weeks = 6, days = listOf(PlanDay("day", "Upper", TemplateRef("routine", "Upper"))),
        progression = ProgressionRule(kind = ProgressionKind.Linear))

    @Test fun allTrackingModesValidateOnlyTheirRequiredInputs() {
        val cases = listOf(
            TrackingMode.WeightReps to PerformedSet(weight = 0.0, reps = 8),
            TrackingMode.BodyweightReps to PerformedSet(weight = 0.0, reps = 8),
            TrackingMode.Reps to PerformedSet(reps = 8), TrackingMode.Duration to PerformedSet(seconds = 30.0),
            TrackingMode.Distance to PerformedSet(metres = 1000.0), TrackingMode.DurationDistance to PerformedSet(seconds = 300.0, metres = 1000.0))
        cases.forEach { (mode, set) ->
            assertNull(TrainingEngine.validateSet(set, mode), mode.toString())
            assertNotNull(TrainingEngine.validateSet(PerformedSet(), mode), mode.toString())
        }
        assertEquals("rpe", TrainingEngine.validateSet(PerformedSet(reps = 8, rpe = 11.0), TrackingMode.Reps))
        assertEquals("weight", TrainingEngine.validateSet(PerformedSet(weight = Double.NaN, reps = 8), TrackingMode.WeightReps))
    }
    @Test fun unitDisplayAndPreviousPrefillDoNotMutateTheRoutineOrHistory() {
        val old = finished(weight = 80.0)
        val before = FtmFormat.encodeDocument(template)
        val previous = FtmFormat.encodeDocument(old)
        val next = TrainingEngine.buildSession(template, listOf(exercise), old, MassUnit.Lb)
        assertEquals(80.0 / TrainingEngine.LB_KG, next.exercises.single().sets.first().weight!!, 0.001)
        assertFalse(next.exercises.single().sets.first().completed)
        assertEquals(before, FtmFormat.encodeDocument(template)); assertEquals(previous, FtmFormat.encodeDocument(old))
        assertEquals(60.0, TrainingEngine.buildSession(template, listOf(exercise), old, prefill = false).exercises.single().sets.first().weight)
    }
    @Test fun volumeCountsOnlyCompletedStrengthSetsAndOptionalWarmups() {
        val doc = finished().let { it.copy(exercises = it.exercises + SessionExercise(snapshot = ExerciseSnapshot(tracking = TrackingMode.Duration),
            sets = listOf(PerformedSet(seconds = 60.0, completed = true)))) }
        assertEquals(1440.0, TrainingEngine.totals(doc).volumeKg)
        assertEquals(3600L, TrainingEngine.totals(doc).seconds)
        val warm = doc.copy(exercises = doc.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(kind = SetKind.Warmup) }) })
        assertEquals(0, TrainingEngine.totals(warm).sets)
        assertEquals(4, TrainingEngine.totals(warm, true).sets)
    }
    @Test fun oneRmFormulasAndPrsAreBoundedAndExcludeFutureHistory() {
        assertEquals(100.0, TrainingEngine.e1rm(100.0, 1))
        assertEquals(120.0, TrainingEngine.e1rm(100.0, 6)!!, 0.001)
        assertEquals(100.0 * 36 / 31, TrainingEngine.e1rm(100.0, 6, OneRmFormula.Brzycki)!!, 0.001)
        assertNull(TrainingEngine.e1rm(100.0, 37))
        val future = finished(weight = 1000.0).copy(endedAt = Instant.parse("2027-01-01T10:00:00Z"))
        assertTrue(TrainingEngine.strengthPrs(finished(weight = 80.0), listOf(finished(weight = 60.0), future)).isNotEmpty())
    }
    @Test fun linearProgressionIncrementsOnlyAfterAllTargetsAreHit() {
        val hit = TrainingEngine.suggestions(plan, finished(), emptyList()).single()
        assertEquals("increase", hit.reason); assertEquals(62.5, hit.targets.first().load!!.amount)
        assertEquals("repeat", TrainingEngine.suggestions(plan, finished(reps = 7), emptyList()).single().reason)
        assertEquals("deload", TrainingEngine.suggestions(plan, finished(reps = 7), List(2) { finished(reps = 7) }).single().reason)
    }
    @Test fun doubleProgressionBuildsRepsBeforeWeightWithoutCountingValidBuildWeeksAsMisses() {
        val double = plan.copy(progression = ProgressionRule(kind = ProgressionKind.Double, repMin = 8, repMax = 12))
        val build = TrainingEngine.suggestions(double, finished(9), List(4) { finished(8) }).single()
        assertEquals("reps", build.reason); assertEquals(10, build.targets.first().reps); assertEquals(60.0, build.targets.first().load!!.amount)
        val hit = TrainingEngine.suggestions(double, finished(12), emptyList()).single()
        assertEquals(8, hit.targets.first().reps); assertEquals(62.5, hit.targets.first().load!!.amount)
    }
    @Test fun manualWeeksAndScheduledDeloadUseTheNextOccurrenceOfTheExercise() {
        val manual = plan.copy(progression = ProgressionRule(kind = ProgressionKind.Manual, manualWeeks = listOf(WeekTarget(2, 75.0, 5))))
        assertEquals(75.0, TrainingEngine.suggestions(manual, finished(), emptyList()).single().targets.first().load!!.amount)
        val deload = plan.copy(deloadEveryWeeks = 2, deloadPercent = 20.0)
        assertEquals(47.5, TrainingEngine.suggestions(deload, finished(), emptyList()).single().targets.first().load!!.amount)
        val lower = WorkoutTemplateDocument(id = "lower", blocks = listOf(WorkoutBlock(exercise = ExerciseRef("squat", "Squat"))))
        val weekly = plan.copy(days = plan.days + PlanDay("lowerDay", "Lower", TemplateRef("lower", "Lower")),
            progression = plan.progression.copy(frequency = ProgressionFrequency.Week))
        assertEquals("increase", TrainingEngine.suggestions(weekly, finished(), emptyList(), listOf(template, lower)).single().reason)
        assertTrue(TrainingEngine.suggestions(plan, finished().copy(planWeek = 6), emptyList()).isEmpty())
    }
    @Test fun plateCalculatorHandlesNonGreedyInventoryAndUnachievableRemainders() {
        val result = TrainingEngine.plates(32.0, 20.0, mapOf(4.0 to 2, 3.0 to 4))
        assertEquals(listOf(3.0, 3.0), result.platesPerSide); assertEquals(0.0, result.remainder)
        assertEquals(1.0, TrainingEngine.plates(33.0, 20.0, mapOf(3.0 to 4)).remainder)
        assertFails { TrainingEngine.plates(10.0, 20.0, emptyMap()) }
    }
    @Test fun warmupsAreRoundedAndExcludedFromSuggestedWorkingLoadChanges() {
        assertEquals(listOf(40.0, 60.0, 80.0), TrainingEngine.warmups(100.0, MassUnit.Kg, 2.5).map { it.load!!.amount })
        val session = finished().let { s -> s.copy(exercises = s.exercises.map { it.copy(sets = listOf(PerformedSet(target = SetTarget(kind = SetKind.Warmup, reps = 8, load = LoadTarget(20.0)), kind = SetKind.Warmup, weight = 20.0, reps = 8, completed = true)) + it.sets) }) }
        val next = TrainingEngine.suggestions(plan, session, emptyList()).single()
        assertEquals(SetKind.Warmup, next.targets.first().kind); assertEquals(20.0, next.targets.first().load!!.amount)
    }
    @Test fun seedCatalogHasStableUniqueEditableExercises() {
        assertTrue(ExerciseCatalog.all.size >= 100)
        assertEquals(ExerciseCatalog.all.size, ExerciseCatalog.all.map { it.id }.distinct().size)
        ExerciseCatalog.all.forEach { assertTrue(TrainingValidation.issues(it).isEmpty(), it.title) }
    }
    @Test fun lenientAiTargetsNormalizeRangesScalarLoadsUnitsAndReferences() {
        val read = FtmReader.read("""{"ftm":1,"documents":[{"type":"workout_template","exercises":[{"exercise":"Bench","sets":[{"reps":"8-12","load":60},{"reps":"5","load":{"amount":"75","unit":"LB"}}]}]}]}""")
        assertTrue(read.errors.isEmpty(), read.issues.toString())
        val sets = (read.documents.single() as WorkoutTemplateDocument).blocks.single().sets
        assertEquals(8, sets[0].repMin); assertEquals(12, sets[0].repMax); assertEquals(60.0, sets[0].load!!.amount); assertEquals(MassUnit.Lb, sets[1].load!!.unit)
    }
    @Test fun invalidCompletedValuesAndPlanBoundsReportPaths() {
        val invalid = finished().let { s -> s.copy(exercises = s.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(weight = null) }) }) }
        assertTrue(TrainingValidation.issues(invalid).any { it.path.contains(".weight") })
        assertTrue(TrainingValidation.issues(plan.copy(overrides = listOf(WeekOverride(7, exercise = ExerciseRef("bench"))))).any { it.path.endsWith(".week") })
    }
    @Test fun fieldMergeKeepsConcurrentCompletionsAndNewExercises() {
        val base = finished(completed = false).normalisedTraining() as WorkoutSessionDocument
        val block = base.exercises.single()
        val current = base.copy(exercises = listOf(block.copy(sets = block.sets.mapIndexed { i, s -> if (i == 1) s.copy(completed = true) else s })) + SessionExercise("new", ExerciseRef("new"), ExerciseSnapshot("New")))
        val edited = base.copy(exercises = listOf(block.copy(sets = block.sets.mapIndexed { i, s -> if (i == 0) s.copy(weight = 72.5) else s })))
        val merged = mergeTrainingEdits(current, base, edited) as WorkoutSessionDocument
        assertEquals(72.5, merged.exercises.first().sets[0].weight)
        assertTrue(merged.exercises.first().sets[1].completed); assertEquals("new", merged.exercises.last().id)
    }
    @Test fun typedCustomTextIsPreservedEvenWhenKeysLookLikeStandardNumericFields() {
        val source = """{"ftm":1,"documents":[{"type":"workout_session","exercises":[{"exercise":{"name":"Custom"},"snapshot":{"name":"Custom","tracking":"reps","fields":[{"key":"weight","kind":"text"},{"key":"status","kind":"text"}]},"sets":[{"reps":8,"completed":true,"customValues":{"weight":"40","status":"completed"}}]}]}]}"""
        val parsed = FtmReader.read(source)
        assertTrue(parsed.errors.isEmpty(), parsed.issues.toString())
        val set = (parsed.documents.single() as WorkoutSessionDocument).exercises.single().sets.single()
        assertEquals(kotlinx.serialization.json.JsonPrimitive("40"), set.customValues["weight"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("completed"), set.customValues["status"])
    }

}
