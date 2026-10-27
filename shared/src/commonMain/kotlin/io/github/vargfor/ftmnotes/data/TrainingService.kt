package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.*
import io.github.vargfor.ftmnotes.core.training.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.di.AppScope
import io.github.vargfor.ftmnotes.platform.AppPlatform
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlin.time.Clock

data class ActiveTraining(val state: ActiveWorkoutEntity, val session: WorkoutSessionDocument)
@Serializable data class PlanCursor(val week: Int = 1, val day: Int = 0)
data class NextWorkout(val plan: TrainingPlanDocument, val cursor: PlanCursor, val day: PlanDay?)

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingService(
    private val repository: DocumentRepository, private val dao: TrainingDao, private val documents: DocumentDao,
    private val prefs: DevicePrefDao, private val transactor: Transactor,
    private val settings: SettingsRepository, private val platform: AppPlatform, private val scope: AppScope,
) {
    private val legacyExerciseIds = ExerciseCatalog.all.map { it.id }.toSet()
    private val ready = CompletableDeferred<Unit>()
    val error = MutableStateFlow<String?>(null)
    val focusExercise = MutableStateFlow<String?>(null)
    val active: StateFlow<ActiveTraining?> = dao.observeActive().flatMapLatest { state ->
        if (state == null) flowOf(null) else repository.observe(state.sessionId).map { stored ->
            (stored?.document as? WorkoutSessionDocument)?.takeIf { it.status == SessionStatus.InProgress && stored.deletedAt == null }
                ?.let { ActiveTraining(state, it) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)
    val cursors = prefs.observeAll().map { rows -> rows.filter { it.key.startsWith("training.plan.") }.associate { it.key to it.value } }
    val exercises = repository.observeDocuments(DocumentType.Exercise).map { it.map { it.document as ExerciseDocument } }
    val templates = repository.observeDocuments(DocumentType.WorkoutTemplate).map { it.map { it.document as WorkoutTemplateDocument } }
    val plans = repository.observeDocuments(DocumentType.TrainingPlan).map { it.map { it.document as TrainingPlanDocument } }
    val sessions = repository.observeDocuments(DocumentType.WorkoutSession).map { it.map { it.document as WorkoutSessionDocument }.sortedByDescending { it.startedAt } }

    init {
        scope.launch {
            try {
                settings.awaitLoaded()
                val bundled = withContext(Dispatchers.Default) { BundledExerciseCatalog.load() }
                transactor.write {
                    val present = documents.all().map { it.id }.toSet()
                    bundled.forEach { exercise ->
                        if (exercise.id !in present) repository.save(exercise)
                        else if (exercise.id in legacyExerciseIds) {
                            val stored = repository.get(exercise.id!!)
                            val existing = stored?.document as? ExerciseDocument
                            if (existing != null && stored.deletedAt == null) {
                                val upgraded = BundledExerciseCatalog.upgrade(existing, exercise)
                                if (upgraded != existing) repository.save(upgraded)
                            }
                        }
                    }
                    val live = dao.active()
                    val session = live?.let { repository.get(it.sessionId) }
                    if (session?.deletedAt != null || session?.document !is WorkoutSessionDocument ||
                        session.document.status != SessionStatus.InProgress) dao.clearActive()
                    val retained = dao.active()?.sessionId
                    repository.documents(DocumentType.WorkoutSession).filterIsInstance<WorkoutSessionDocument>()
                        .filter { it.status == SessionStatus.InProgress && it.id != retained }.forEach { doc ->
                            repository.update(doc.id!!) { (it as WorkoutSessionDocument).copy(status = SessionStatus.Draft) }
                        }
                }
                ready.complete(Unit)
            } catch (e: CancellationException) { ready.cancel(e); throw e }
            catch (e: Exception) { error.value = e.message; ready.completeExceptionally(e) }
        }
    }
    suspend fun awaitReady() = ready.await()

    suspend fun start(templateId: String? = null, title: String = "", planId: String? = null,
                      week: Int? = null, dayId: String? = null): String {
        awaitReady()
        val id = transactor.write {
            dao.active()?.let { return@write it.sessionId }
            var template = templateId?.let { repository.get(it)?.document as? WorkoutTemplateDocument ?: error("Routine is unavailable") }
            val plan = planId?.let { repository.get(it)?.document as? TrainingPlanDocument ?: error("Plan is unavailable") }
            val catalog = repository.documents(DocumentType.Exercise).filterIsInstance<ExerciseDocument>().toMutableList()
            template?.blocks?.forEach { block -> if (catalog.none { it.id == block.exercise.id }) {
                val referenced = block.exercise.id?.let { repository.get(it)?.document as? ExerciseDocument }
                if (referenced != null) catalog += referenced
            } }
            val history = repository.documents(DocumentType.WorkoutSession).filterIsInstance<WorkoutSessionDocument>()
                .filter { it.status == SessionStatus.Finished }.sortedByDescending { it.endedAt }
            if (template != null && plan != null && week != null) {
                template = template.copy(blocks = template.blocks.map { b ->
                    val override = plan.overrides.lastOrNull { it.week == week && (it.dayId == null || it.dayId == dayId) &&
                        (it.exercise.id == b.exercise.id || TrainingEngine.key(it.exercise.name) == TrainingEngine.key(b.exercise.name)) }
                    if (override != null) b.copy(sets = override.sets) else b
                })
            }
            val previous = if (settings.snapshot.value[Flags.trainingPreviousGlobal]) {
                val seen = mutableSetOf<String>()
                WorkoutSessionDocument(exercises = history.flatMap { it.exercises }.filter { seen.add(it.exercise.id ?: it.exercise.name) })
            } else history.firstOrNull { it.templateId == templateId }
            var session = TrainingEngine.buildSession(template, catalog, previous, settings.snapshot.value[Flags.trainingUnits],
                settings.snapshot.value[Flags.trainingRestSeconds], settings.snapshot.value[Flags.trainingPrefill] && plan == null)
                .copy(title = template?.title ?: title, startedAt = Clock.System.now(), planId = planId, planWeek = week, planDayId = dayId)
            val maxes = settings.snapshot.value[Flags.trainingMaxes]
            val tms = settings.snapshot.value[Flags.trainingTms]
            session = session.copy(exercises = session.exercises.map { e -> e.copy(sets = e.sets.map { s ->
                val load = s.target.load
                if (load?.kind in listOf(LoadKind.PercentOneRm, LoadKind.PercentTrainingMax)) {
                    val best = history.flatMap { it.exercises }.filter { it.exercise.id == e.exercise.id }.flatMap { it.sets }
                        .filter { it.completed && it.kind != SetKind.Warmup }.mapNotNull { TrainingEngine.e1rm(TrainingEngine.kg(it.weight ?: 0.0, it.unit), it.reps ?: 0) }.maxOrNull()
                    val max = if (load!!.kind == LoadKind.PercentTrainingMax) tms[e.exercise.id] ?: (maxes[e.exercise.id] ?: best)?.times(0.9) else maxes[e.exercise.id] ?: best
                    s.copy(weight = max?.let { TrainingEngine.rounded(TrainingEngine.mass(it * load.amount / 100, MassUnit.Kg, s.unit), e.snapshot.increment) })
                } else s
            }) })
            val saved = repository.save(session)
            dao.putActive(ActiveWorkoutEntity(sessionId = saved))
            saved
        }
        platform.bridge.workoutChanged(id)
        return id
    }
    suspend fun resume(id: String): String {
        awaitReady()
        val result = transactor.write {
            dao.active()?.let { return@write it.sessionId }
            val stored = repository.get(id) ?: error("Workout is unavailable")
            require(stored.deletedAt == null)
            val session = stored.document as WorkoutSessionDocument
            require(session.status == SessionStatus.Draft) { "This workout is already finished" }
            repository.update(id) { (it as WorkoutSessionDocument).copy(status = SessionStatus.InProgress, startedAt = session.startedAt ?: Clock.System.now(), endedAt = null) }
            dao.putActive(ActiveWorkoutEntity(sessionId = id)); id
        }
        platform.bridge.workoutChanged(result); return result
    }
    suspend fun <T> atomic(block: suspend () -> T): T = transactor.write(block)

    suspend fun mutate(id: String, transform: (WorkoutSessionDocument) -> WorkoutSessionDocument) {
        repository.update(id) { if (it is WorkoutSessionDocument) transform(it) else it }
    }
    suspend fun editSet(id: String, exerciseId: String, setId: String, transform: (PerformedSet) -> PerformedSet) =
        mutate(id) { doc -> doc.copy(exercises = doc.exercises.map { e -> if (e.id != exerciseId) e else e.copy(sets = e.sets.map { if (it.id == setId) transform(it) else it }) }) }

    suspend fun complete(id: String, exerciseId: String, setId: String) {
        var rest: Int? = null
        var next: String? = null
        transactor.write {
            val stored = repository.get(id)?.document as? WorkoutSessionDocument ?: return@write
            val e = stored.exercises.first { it.id == exerciseId }
            val set = e.sets.first { it.id == setId }
            if (!set.completed) {
                require(TrainingEngine.validateSet(set, e.snapshot.tracking) == null) { "Enter valid values before completing this set" }
                e.snapshot.fields.forEach { field -> set.customValues[field.key]?.let { value ->
                    val primitive = value as? kotlinx.serialization.json.JsonPrimitive
                    require(when (field.kind) {
                        FieldKind.Number -> primitive?.content?.toDoubleOrNull()?.isFinite() == true
                        FieldKind.Boolean -> primitive?.content in listOf("true", "false")
                        FieldKind.Text -> primitive?.isString == true
                    }) { "Invalid custom field: " + field.label }
                } }
            }
            editSet(id, exerciseId, setId) { it.copy(completed = !set.completed) }
            if (!set.completed && stored.status == SessionStatus.InProgress) {
                if (settings.snapshot.value[Flags.trainingRest]) rest = e.restSeconds.takeIf { it > 0 }
                if (settings.snapshot.value[Flags.trainingSupersets] && e.supersetGroup != null) {
                    val group = stored.exercises.filter { it.supersetGroup == e.supersetGroup }
                    val pos = group.indexOfFirst { it.id == exerciseId }
                    next = (group.drop(pos + 1) + group.take(pos + 1)).firstOrNull { other ->
                        other.id != exerciseId && other.sets.any { !it.completed }
                    }?.id
                }
            }
        }
        focusExercise.value = next
        rest?.let { startRest(exerciseId, it) }
    }
    suspend fun addExercise(id: String, exerciseId: String) {
        val exercise = repository.get(exerciseId)?.document as? ExerciseDocument ?: error("Exercise unavailable")
        val template = WorkoutTemplateDocument(blocks = listOf(WorkoutBlock(exercise = ExerciseRef(exercise.id, exercise.title), sets = listOf(SetTarget(reps = if (exercise.tracking.hasReps) 8 else null)))))
        val built = TrainingEngine.buildSession(template, listOf(exercise), unit = settings.snapshot.value[Flags.trainingUnits]).exercises.single()
        mutate(id) { it.copy(exercises = it.exercises + built) }
        focusExercise.value = built.id
    }
    suspend fun replaceExercise(id: String, blockId: String, exerciseId: String) {
        val exercise = repository.get(exerciseId)?.document as? ExerciseDocument ?: error("Exercise unavailable")
        mutate(id) { doc -> doc.copy(exercises = doc.exercises.map { e ->
            if (e.id != blockId) e else e.copy(exercise = ExerciseRef(exercise.id, exercise.title, exercise.tracking, exercise.equipment),
                snapshot = ExerciseSnapshot(exercise.title, exercise.tracking, exercise.equipment, exercise.muscleGroups, exercise.fields, exercise.increment),
                restSeconds = exercise.defaultRestSeconds, sets = e.sets.map { s -> s.copy(completed = false) })
        }) }
    }
    suspend fun startRest(exerciseId: String?, seconds: Int) {
        require(seconds in 1..604800)
        val state = transactor.write {
            val old = dao.active() ?: return@write null
            val millis = seconds * 1000L
            old.copy(restEnd = Clock.System.now().toEpochMilliseconds() + millis,
                elapsedEnd = platform.bridge.elapsedRealtimeMillis()?.plus(millis), bootId = platform.bridge.bootId(),
                restToken = Ids.newId(), alertedToken = null, exerciseId = exerciseId).also { dao.putActive(it) }
        }
        state?.let { platform.bridge.workoutChanged(it.sessionId) }
    }
    suspend fun extendRest(seconds: Int) {
        val current = dao.active() ?: return
        val remaining = remainingMillis(current, Clock.System.now().toEpochMilliseconds(), platform.bridge.elapsedRealtimeMillis(), platform.bridge.bootId())
        startRest(current.exerciseId, ((remaining.coerceAtLeast(0) + 999) / 1000 + seconds).toInt().coerceAtLeast(1))
    }
    suspend fun skipRest() {
        val current = transactor.write {
            dao.active()?.copy(restEnd = null, elapsedEnd = null, restToken = null, alertedToken = null, exerciseId = null)?.also { dao.putActive(it) }
        }
        current?.let { platform.bridge.workoutChanged(it.sessionId) }
    }
    suspend fun claimRestAlert(token: String): Boolean = transactor.write {
        val row = dao.active() ?: return@write false
        if (row.restToken != token || row.alertedToken == token || row.restEnd == null) return@write false
        if (remainingMillis(row, Clock.System.now().toEpochMilliseconds(), platform.bridge.elapsedRealtimeMillis(), platform.bridge.bootId()) > 500) return@write false
        dao.putActive(row.copy(alertedToken = token)); true
    }
    suspend fun finish(id: String): WorkoutSessionDocument {
        awaitReady()
        val result = transactor.write {
            val session = repository.get(id)?.document as? WorkoutSessionDocument ?: error("Workout unavailable")
            if (session.status == SessionStatus.Finished) return@write session
            require(session.status == SessionStatus.InProgress && dao.active()?.sessionId == id) { "Workout is not active" }
            val finished = session.copy(status = SessionStatus.Finished, endedAt = Clock.System.now())
            repository.save(finished); dao.clearActive()
            if (settings.snapshot.value[Flags.trainingAutoAdvance]) advanceFrom(finished)
            finished
        }
        platform.bridge.workoutChanged(null)
        return result
    }
    suspend fun discard(id: String) {
        transactor.write { repository.moveToTrash(id); if (dao.active()?.sessionId == id) dao.clearActive() }
        platform.bridge.workoutChanged(dao.active()?.sessionId)
    }
    suspend fun updateTemplate(sessionId: String) {
        val session = repository.get(sessionId)?.document as? WorkoutSessionDocument ?: return
        val new = TrainingEngine.toTemplate(session, session.templateId)
        if (new.id == null) repository.save(new) else repository.update(new.id!!) { current ->
            if (current is WorkoutTemplateDocument) current.copy(title = new.title, blocks = new.blocks, notes = new.notes) else current
        }
    }
    suspend fun saveAsTemplate(sessionId: String): String {
        val session = repository.get(sessionId)?.document as? WorkoutSessionDocument ?: error("Workout unavailable")
        return repository.save(TrainingEngine.toTemplate(session))
    }
    suspend fun cursor(plan: TrainingPlanDocument): PlanCursor = prefs.get("training.plan." + plan.id)?.let {
        runCatching { FtmFormat.compact.decodeFromString(PlanCursor.serializer(), it) }.getOrNull()
    }?.takeIf { it.week >= 1 && it.day in plan.days.indices } ?: PlanCursor()
    suspend fun setCursor(planId: String, value: PlanCursor) {
        val plan = repository.get(planId)?.document as? TrainingPlanDocument ?: error("Plan unavailable")
        require(value.week in 1..plan.weeks && value.day in plan.days.indices)
        prefs.put(DevicePrefEntity("training.plan." + planId, FtmFormat.compact.encodeToString(PlanCursor.serializer(), value)))
    }
    suspend fun nextWorkout(planId: String): NextWorkout? {
        val plan = repository.get(planId)?.document as? TrainingPlanDocument ?: return null
        val cursor = cursor(plan)
        return NextWorkout(plan, cursor, plan.days.getOrNull(cursor.day)?.takeIf { cursor.week <= plan.weeks })
    }
    suspend fun startNext(planId: String): String {
        val next = nextWorkout(planId) ?: error("Plan unavailable")
        val day = next.day ?: error("Plan is complete")
        return start(day.template.id, planId = planId, week = next.cursor.week, dayId = day.id)
    }
    suspend fun advanceFrom(session: WorkoutSessionDocument) {
        val plan = session.planId?.let { repository.get(it)?.document as? TrainingPlanDocument } ?: return
        val old = cursor(plan)
        if (old.week != session.planWeek || plan.days.getOrNull(old.day)?.id != session.planDayId) return
        val next = if (old.day + 1 < plan.days.size) old.copy(day = old.day + 1) else PlanCursor(old.week + 1, 0)
        prefs.put(DevicePrefEntity("training.plan." + plan.id, FtmFormat.compact.encodeToString(PlanCursor.serializer(), next)))
    }
    suspend fun suggestions(sessionId: String): List<ProgressionSuggestion> {
        if (!settings.snapshot.value[Flags.trainingSuggestions]) return emptyList()
        val session = repository.get(sessionId)?.document as? WorkoutSessionDocument ?: return emptyList()
        val plan = session.planId?.let { repository.get(it)?.document as? TrainingPlanDocument } ?: return emptyList()
        val history = repository.documents(DocumentType.WorkoutSession).filterIsInstance<WorkoutSessionDocument>().filter { it.id != sessionId }
        return TrainingEngine.suggestions(plan, session, history, repository.documents(DocumentType.WorkoutTemplate).filterIsInstance<WorkoutTemplateDocument>())
    }
    suspend fun acceptSuggestions(sessionId: String, exerciseIds: Set<String>, setIds: Set<String>? = null) {
        val session = repository.get(sessionId)?.document as? WorkoutSessionDocument ?: return
        val planId = session.planId ?: return
        val suggestions = suggestions(sessionId).filter { it.exerciseId in exerciseIds }
        val plan = repository.get(planId)?.document as? TrainingPlanDocument ?: return
        if (plan.days.isEmpty()) return
        val templates = repository.documents(DocumentType.WorkoutTemplate).filterIsInstance<WorkoutTemplateDocument>().associateBy { it.id }
        val cursor = cursor(plan)
        val sessionPosition = ((session.planWeek ?: 1) - 1) * plan.days.size + plan.days.indexOfFirst { it.id == session.planDayId }.coerceAtLeast(0)
        val earliest = maxOf(sessionPosition + 1, (cursor.week - 1) * plan.days.size + cursor.day)
        val changes = suggestions.mapNotNull { suggestion ->
            val position = (earliest until plan.weeks * plan.days.size).firstOrNull { position ->
                templates[plan.days[position % plan.days.size].template.id]?.blocks?.any { it.exercise.id == suggestion.exerciseId } == true
            } ?: return@mapNotNull null
            WeekOverride(position / plan.days.size + 1, plan.days[position % plan.days.size].id,
                session.exercises.first { it.exercise.id == suggestion.exerciseId }.exercise, suggestion.targets)
        }
        repository.update(planId) { doc ->
            doc as TrainingPlanDocument
            val applied = changes.map { change ->
                if (setIds == null) change else {
                    val original = doc.overrides.lastOrNull { it.week == change.week && (it.dayId == null || it.dayId == change.dayId) && it.exercise.id == change.exercise.id }?.sets
                        ?: templates[doc.days.firstOrNull { it.id == change.dayId }?.template?.id]?.blocks?.firstOrNull { it.exercise.id == change.exercise.id }?.sets.orEmpty()
                    val performed = session.exercises.first { it.exercise.id == change.exercise.id }.sets
                    change.copy(sets = change.sets.mapIndexed { i, target -> if (target.id in setIds) target else
                        original.firstOrNull { it.id == target.id } ?: original.getOrNull(i) ?: performed.getOrNull(i)?.target ?: target })
                }
            }
            doc.copy(overrides = doc.overrides.filterNot { old -> applied.any { it.week == old.week && it.dayId == old.dayId && it.exercise.id == old.exercise.id } } + applied)
        }
    }

    companion object {
        fun remainingMillis(state: ActiveWorkoutEntity, wall: Long, elapsed: Long?, bootId: Long?): Long =
            if (state.elapsedEnd != null && elapsed != null && state.bootId != null && state.bootId == bootId) state.elapsedEnd - elapsed
            else (state.restEnd ?: wall) - wall
    }
}
