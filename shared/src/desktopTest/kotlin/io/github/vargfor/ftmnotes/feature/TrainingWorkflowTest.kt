package io.github.vargfor.ftmnotes.feature

import io.github.vargfor.ftmnotes.core.backup.*
import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.feature.training.*
import io.github.vargfor.ftmnotes.resources.Res
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.FileSystem
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalResourceApi::class)
class TrainingWorkflowTest {
    private fun scenario(block: suspend ScreenTestEnvironment.() -> Unit) = runBlocking {
        val env = ScreenTestEnvironment()
        try { env.ready(); env.block() } finally { env.close() }
    }
    private suspend fun ScreenTestEnvironment.program(): TrainingPlanDocument {
        val input = Res.readBytes("files/example_training_program.ftm.json").decodeToString()
        val preview = imports.prepareFtm(input)
        assertTrue(preview.questions.isEmpty(), preview.questions.toString())
        val plan = preview.drafts.first { it.document is TrainingPlanDocument }
        imports.commit(preview.id, setOf(plan.id), emptyList())
        return repository.get(plan.document.id!!)!!.document as TrainingPlanDocument
    }
    private suspend fun ScreenTestEnvironment.session(id: String) = repository.get(id)!!.document as WorkoutSessionDocument
    private fun ScreenTestEnvironment.backups() = BackupService(bridge, attachments, repository, db.documentDao(), db.revisionDao(),
        db.devicePrefDao(), settings, transactor, clock, training = db.trainingDao())

    @Test fun fullProgramImportFinishProgressionExportAndFreshDatabaseImport() = scenario {
        val plan = program()
        assertEquals(2, plan.days.size)
        assertTrue(TrainingBundles.validateReferences(listOf(plan), repository.documents(DocumentType.WorkoutTemplate) + repository.documents(DocumentType.Exercise)).isEmpty())
        val id = training.startNext(plan.id!!)
        assertEquals(id, training.start())
        var live = session(id)
        assertEquals(plan.id, live.planId); assertEquals(1, live.planWeek)
        live.exercises.forEach { e -> e.sets.forEach { set ->
            training.editSet(id, e.id!!, set.id!!) { it.copy(weight = 60.0, reps = 12) }
            training.complete(id, e.id!!, set.id!!)
        } }
        live = session(id)
        assertTrue(live.exercises.flatMap { it.sets }.all { it.completed })
        assertTrue(db.trainingDao().exerciseHistory(live.exercises.first().exercise.id!!).first().isEmpty())
        val finished = training.finish(id)
        assertEquals(SessionStatus.Finished, finished.status); assertNull(db.trainingDao().active())
        assertTrue(db.trainingDao().exerciseHistory(live.exercises.first().exercise.id!!).first().isNotEmpty())
        assertEquals(0, training.cursor(plan).day) // progression remains a review action by default
        assertTrue(training.suggestions(id).isNotEmpty())
        training.acceptSuggestions(id, finished.exercises.mapNotNull { it.exercise.id }.toSet())
        training.advanceFrom(finished)
        assertEquals(1, training.cursor(plan).day)
        training.advanceFrom(finished); assertEquals(1, training.cursor(plan).day)
        val file = imports.exportSelection(setOf(id))
        val exported = FileSystem.SYSTEM.read(file) { readUtf8() }
        val parsed = FtmReader.read(exported)
        assertTrue(parsed.errors.isEmpty(), parsed.issues.toString())
        assertTrue(parsed.documents.any { it is TrainingPlanDocument })
        val fresh = ScreenTestEnvironment()
        try {
            fresh.ready()
            val preview = fresh.imports.prepareFtm(exported)
            assertTrue(preview.questions.isEmpty())
            fresh.imports.commit(preview.id, preview.drafts.filterNot { it.duplicate }.map { it.id }.toSet(), emptyList())
            val restored = fresh.repository.documents(DocumentType.WorkoutSession).filterIsInstance<WorkoutSessionDocument>().single()
            assertEquals(TrainingEngine.totals(finished), TrainingEngine.totals(restored))
            assertTrue(TrainingBundles.validateReferences(listOf(restored), fresh.repository.documents(DocumentType.TrainingPlan) + fresh.repository.documents(DocumentType.WorkoutTemplate) + fresh.repository.documents(DocumentType.Exercise)).isEmpty())
            val repeated = fresh.imports.prepareFtm(exported)
            assertTrue(repeated.drafts.all { it.duplicate })
        } finally { fresh.close() }
    }
    @Test fun dependencyUndoRetainsExercisesAndRoutinesUsedByAWorkout() = scenario {
        val preview = imports.prepareFtm(Res.readBytes("files/example_training_program.ftm.json").decodeToString())
        val plan = preview.drafts.first { it.document is TrainingPlanDocument }
        imports.commit(preview.id, setOf(plan.id), emptyList())
        val id = training.startNext(plan.document.id!!)
        val live = session(id)
        imports.undo(preview.id)
        assertNotNull(repository.get(live.templateId!!)!!.takeIf { it.deletedAt == null })
        live.exercises.forEach { assertNull(repository.get(it.exercise.id!!)!!.deletedAt) }
        assertNull(repository.get(live.planId!!)!!.deletedAt)
        assertEquals(id, db.trainingDao().active()?.sessionId)
    }
    @Test fun unresolvedReferencesBlockTheWholeImportWithoutPartialWrites() = scenario {
        repository.save(ExerciseDocument("a", "Ambiguous lift", equipment = "barbell"))
        repository.save(ExerciseDocument("b", "Ambiguous lift", equipment = "dumbbell"))
        repository.save(NoteDocument("collision", "Original"))
        val source = FtmFormat.write(listOf(WorkoutTemplateDocument("collision", "Review", blocks = listOf(WorkoutBlock(exercise = ExerciseRef(name = "Ambiguous lift"))))))
        val first = imports.prepareFtm(source)
        assertEquals(1, first.questions.size)
        assertFails { imports.commit(first.id, first.drafts.map { it.id }.toSet(), emptyList()) }
        assertTrue(repository.documents(DocumentType.WorkoutTemplate).isEmpty())
        val fixed = imports.resolveReference(first.id, first.questions.single().key, "b")
        assertTrue(fixed.questions.isEmpty())
        assertEquals(first.drafts.first().id, fixed.drafts.first().id)
        imports.commit(fixed.id, fixed.drafts.map { it.id }.toSet(), emptyList())
        assertEquals("Original", repository.get("collision")!!.document.title)
    }
    @Test fun numericFlushSurvivesViewModelClearAndCompletionAndUndoStayTransactional() = scenario {
        val plan = program(); val id = training.startNext(plan.id!!)
        val vm = model("workout") { WorkoutViewModel(id, repository, training, scope) }
        await { vm.loaded && vm.session != null }
        val e = session(id).exercises.first(); val s = e.sets.first()
        onMain { vm.edit(e.id!!, s.id!!, "weight") { it.copy(weight = 72.5) }; vm.edit(e.id!!, s.id!!, "reps") { it.copy(reps = 9) } }
        withContext(Dispatchers.Main) { assertTrue(vm.flushNow()) }
        assertEquals(72.5, session(id).exercises.first().sets.first().weight)
        onMain { vm.action { training.complete(id, e.id!!, s.id!!) } }
        await { session(id).exercises.first().sets.first().completed }
        repository.update(id) { it.withOrganisation(tags = listOf("Concurrent")) }
        onMain { vm.undo() }
        await { !session(id).exercises.first().sets.first().completed }
        assertEquals(listOf("Concurrent"), session(id).tags)
        onMain { vm.edit(e.id!!, s.id!!, "weight") { it.copy(weight = 80.0) }; models.clear() }
        await { session(id).exercises.first().sets.first().weight == 80.0 }
    }
    @Test fun restClocksAndTokensHandleExtensionSkipBootChangeAndDuplicateAlerts() = scenario {
        val id = training.start(title = "Timer")
        training.startRest(null, 90)
        val first = db.trainingDao().active()!!
        assertEquals(90_000, TrainingService.remainingMillis(first, first.restEnd!! - 90_000, null, null))
        assertFalse(training.claimRestAlert(first.restToken!!))
        training.extendRest(15)
        val extended = db.trainingDao().active()!!
        assertNotEquals(first.restToken, extended.restToken)
        assertTrue(extended.restEnd!! > first.restEnd)
        assertFalse(training.claimRestAlert(first.restToken))
        val elapsed = extended.copy(elapsedEnd = 5000, bootId = 7)
        assertEquals(3000, TrainingService.remainingMillis(elapsed, extended.restEnd - 10000, 2000, 7))
        assertEquals(10000, TrainingService.remainingMillis(elapsed, extended.restEnd - 10000, 2000, 8))
        db.trainingDao().putActive(extended.copy(restEnd = Clock.System.now().toEpochMilliseconds() - 1000, elapsedEnd = null, bootId = null))
        assertTrue(training.claimRestAlert(extended.restToken!!)); assertFalse(training.claimRestAlert(extended.restToken))
        training.skipRest(); assertNull(db.trainingDao().active()!!.restToken)
        training.finish(id); assertNull(db.trainingDao().active())
    }
    @Test fun backupRecoversAnActiveWorkoutAndTimerButPreservesAnotherCurrentWorkout() = scenario {
        val id = training.start(title = "Recovered")
        training.startRest(null, 60)
        db.devicePrefDao().put(DevicePrefEntity("training.plan.fixture", """{"week":3,"day":1}"""))
        val archive = backups().create()
        val fresh = ScreenTestEnvironment()
        try {
            fresh.ready()
            fresh.backups().restore(archive, " (restored)")
            assertEquals(id, fresh.db.trainingDao().active()!!.sessionId)
            assertNotNull(fresh.db.trainingDao().active()!!.restEnd)
            assertEquals(SessionStatus.InProgress, fresh.session(id).status)
            assertNotNull(fresh.db.devicePrefDao().get("training.plan.fixture"))
            fresh.training.finish(id)
            val current = fresh.training.start(title = "Current")
            fresh.backups().restore(archive, " (restored)")
            assertEquals(current, fresh.db.trainingDao().active()!!.sessionId)
            assertEquals("Current", fresh.session(current).title)
        } finally { fresh.close() }
    }
    @Test fun restoringAnOlderActiveSnapshotDoesNotReplaceTheCurrentSessionOrMultiplyDrafts() = scenario {
        val id = training.start(title = "Original")
        val archive = backups().create()
        training.mutate(id) { it.copy(title = "Current edits") }
        assertEquals(1, backups().restore(archive, " (restored)").conflicts)
        assertEquals(0, backups().restore(archive, " (restored)").conflicts)
        assertEquals(id, db.trainingDao().active()!!.sessionId)
        assertEquals("Current edits", session(id).title)
        assertEquals(1, repository.documents(DocumentType.WorkoutSession).filterIsInstance<WorkoutSessionDocument>().count { it.status == SessionStatus.Draft })
        val fresh = ScreenTestEnvironment()
        try {
            fresh.ready(); val other = fresh.training.start(title = "Other current workout")
            fresh.backups().restore(archive, " (restored)")
            assertEquals(other, fresh.db.trainingDao().active()!!.sessionId)
            assertEquals(SessionStatus.Draft, fresh.session(id).status)
        } finally { fresh.close() }
    }

    @Test fun contentExportExcludesLiveWorkoutsAndTrashRestoreKeepsRecoverableDrafts() = scenario {
        val id = training.start(title = "Live")
        val exported = backups().exportFtm()
        assertTrue(FtmReader.read(FileSystem.SYSTEM.read(exported) { readUtf8() }).documents.none { it is WorkoutSessionDocument })
        assertFails { imports.exportSelection(setOf(id)) }
        training.discard(id); assertNull(db.trainingDao().active())
        repository.restore(id)
        assertEquals(SessionStatus.Draft, session(id).status)
        assertEquals(id, training.resume(id))
    }
    @Test fun acceptingOneSetPreservesOtherFutureTargetsAndPreviouslyAcceptedSets() = scenario {
        val plan = program(); val id = training.startNext(plan.id!!)
        val session = session(id)
        session.exercises.forEach { e -> e.sets.forEach { set ->
            training.editSet(id, e.id!!, set.id!!) { it.copy(reps = 12) }; training.complete(id, e.id!!, set.id!!)
        } }
        training.finish(id)
        val suggestion = training.suggestions(id).first()
        val exerciseId = suggestion.exerciseId!!
        val first = suggestion.targets[0]; val second = suggestion.targets[1]
        training.acceptSuggestions(id, setOf(exerciseId), setOf(first.id!!))
        fun targetPlan(doc: FtmDocument) = (doc as TrainingPlanDocument).overrides.last { it.exercise.id == exerciseId && it.week == 2 }
        val partial = targetPlan(repository.get(plan.id!!)!!.document)
        assertEquals(first.load!!.amount, partial.sets[0].load!!.amount)
        assertEquals(session.exercises.first().sets[1].target.load!!.amount, partial.sets[1].load!!.amount)
        training.acceptSuggestions(id, setOf(exerciseId), setOf(second.id!!))
        val next = targetPlan(repository.get(plan.id!!)!!.document)
        assertEquals(first.load!!.amount, next.sets[0].load!!.amount)
        assertEquals(second.load!!.amount, next.sets[1].load!!.amount)
        training.setCursor(plan.id!!, PlanCursor(3, 1))
        assertEquals(PlanCursor(3, 1), training.nextWorkout(plan.id!!)!!.cursor)
    }

    @Test fun exerciseSnapshotRemainsHistoricalAfterCatalogEditsAndPercentTargetsUseMaxes() = scenario {
        val exercise = repository.save(ExerciseDocument(title = "Percent test", tracking = TrackingMode.WeightReps))
        settings.set(Flags.trainingMaxes, mapOf(exercise to 100.0))
        settings.snapshot.first { it[Flags.trainingMaxes][exercise] == 100.0 }
        val template = repository.save(WorkoutTemplateDocument(title = "Percent routine", blocks = listOf(WorkoutBlock(exercise = ExerciseRef(exercise, "Percent test"),
            sets = listOf(SetTarget(reps = 5, load = LoadTarget(75.0, kind = LoadKind.PercentOneRm)))))))
        val id = training.start(template)
        assertEquals(75.0, session(id).exercises.single().sets.single().weight)
        repository.update(exercise) { (it as ExerciseDocument).copy(title = "Renamed", tracking = TrackingMode.Duration) }
        assertEquals("Percent test", session(id).exercises.single().snapshot.name)
        assertEquals(TrackingMode.WeightReps, session(id).exercises.single().snapshot.tracking)
    }
}
