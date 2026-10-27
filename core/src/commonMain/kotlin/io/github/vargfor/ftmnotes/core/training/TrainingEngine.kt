package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.model.*
import kotlin.math.*

enum class OneRmFormula { Epley, Brzycki }
data class SessionTotals(val sets: Int, val reps: Int, val volumeKg: Double, val seconds: Long)
data class ProgressionSuggestion(val exerciseId: String?, val targets: List<SetTarget>, val reason: String)
data class PlateResult(val platesPerSide: List<Double>, val achievedWeight: Double, val remainder: Double)

object TrainingEngine {
    const val LB_KG = 0.45359237
    fun kg(value: Double, unit: MassUnit) = if (unit == MassUnit.Lb) value * LB_KG else value
    fun mass(value: Double, from: MassUnit, to: MassUnit) = if (to == MassUnit.Kg) kg(value, from) else kg(value, from) / LB_KG
    fun rounded(value: Double, increment: Double): Double {
        require(value.isFinite() && increment.isFinite() && increment > 0)
        return (round(value / increment) * increment).coerceAtLeast(0.0)
    }
    fun e1rm(weight: Double, reps: Int, formula: OneRmFormula = OneRmFormula.Epley): Double? {
        if (!weight.isFinite() || weight <= 0 || reps !in 1..36) return null
        if (reps == 1) return weight
        return if (formula == OneRmFormula.Epley) weight * (1 + reps / 30.0) else weight * 36.0 / (37 - reps)
    }
    fun totals(session: WorkoutSessionDocument, warmups: Boolean = false): SessionTotals {
        val sets = session.exercises.flatMap { e -> e.sets.filter { it.completed && (warmups || it.kind != SetKind.Warmup) }.map { e to it } }
        return SessionTotals(sets.size, sets.sumOf { it.second.reps ?: 0 },
            sets.sumOf { (e, s) -> if (e.snapshot.tracking == TrackingMode.WeightReps) kg(s.weight ?: 0.0, s.unit) * (s.reps ?: 0) else 0.0 },
            if (session.startedAt != null && session.endedAt != null) (session.endedAt - session.startedAt).inWholeSeconds.coerceAtLeast(0) else 0)
    }
    fun validateSet(set: PerformedSet, tracking: TrackingMode): String? = when {
        tracking.hasWeight && (set.weight == null || !set.weight.isFinite() || set.weight < 0) -> "weight"
        tracking.hasReps && (set.reps == null || set.reps <= 0) -> "reps"
        tracking.hasDuration && (set.seconds == null || !set.seconds.isFinite() || set.seconds <= 0) -> "seconds"
        tracking.hasDistance && (set.metres == null || !set.metres.isFinite() || set.metres <= 0) -> "metres"
        set.rpe != null && (!set.rpe.isFinite() || set.rpe !in 1.0..10.0) -> "rpe"
        set.rir != null && (!set.rir.isFinite() || set.rir !in 0.0..10.0) -> "rir"
        else -> null
    }

    fun buildSession(template: WorkoutTemplateDocument?, exercises: List<ExerciseDocument>, previous: WorkoutSessionDocument? = null,
                     unit: MassUnit = MassUnit.Kg, defaultRest: Int = 90, prefill: Boolean = true): WorkoutSessionDocument {
        val byId = exercises.associateBy { it.id }
        val blocks = template?.blocks.orEmpty().map { block ->
            val exercise = byId[block.exercise.id] ?: exercises.singleOrNull { matches(block.exercise, it) }
                ?: error("Unresolved exercise: " + block.exercise.name)
            val targetUnit = exercise.unitOverride ?: unit
            val old = previous?.exercises?.lastOrNull { it.exercise.id == exercise.id }
            SessionExercise(Ids.newId(), ExerciseRef(exercise.id, exercise.title, exercise.tracking, exercise.equipment),
                ExerciseSnapshot(exercise.title, exercise.tracking, exercise.equipment, exercise.muscleGroups, exercise.fields, exercise.increment),
                block.supersetGroup, block.restSeconds ?: exercise.defaultRestSeconds.takeIf { it >= 0 } ?: defaultRest, block.notes,
                block.sets.mapIndexed { i, target ->
                    val prior = old?.sets?.filter { it.kind == target.kind }?.getOrNull(block.sets.take(i).count { it.kind == target.kind })?.takeIf { prefill && it.completed }
                    val load = target.load
                    PerformedSet(Ids.newId(), target, target.kind,
                        weight = prior?.weight?.let { mass(it, prior.unit, targetUnit) }
                            ?: load?.takeIf { it.kind == LoadKind.Absolute }?.let { mass(it.amount, it.unit, targetUnit) }
                            ?: if (exercise.tracking == TrackingMode.BodyweightReps) 0.0 else null,
                        unit = targetUnit, reps = prior?.reps ?: target.reps ?: target.repMin,
                        seconds = prior?.seconds ?: target.seconds, metres = prior?.metres ?: target.metres,
                        rpe = load?.takeIf { it.kind == LoadKind.Rpe }?.amount,
                        rir = load?.takeIf { it.kind == LoadKind.Rir }?.amount, customValues = target.customValues)
                })
        }
        return WorkoutSessionDocument(id = Ids.newId(), title = template?.title.orEmpty(), status = SessionStatus.InProgress,
            templateId = template?.id, templateName = template?.title.orEmpty(), exercises = blocks)
    }
    fun matches(ref: ExerciseRef, exercise: ExerciseDocument): Boolean =
        (ref.tracking == null || ref.tracking == exercise.tracking) &&
        (ref.equipment.isNullOrBlank() || key(ref.equipment) == key(exercise.equipment)) &&
        (ref.id == exercise.id && ref.id != null || key(ref.name) in (exercise.aliases + exercise.title).map(::key))
    fun key(text: String) = text.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun suggestions(plan: TrainingPlanDocument, session: WorkoutSessionDocument, history: List<WorkoutSessionDocument>, templates: List<WorkoutTemplateDocument> = emptyList()): List<ProgressionSuggestion> =
        session.exercises.mapNotNull { exercise ->
            val rule = plan.exerciseRules.firstOrNull { it.exercise.id == exercise.exercise.id ||
                key(it.exercise.name) == key(exercise.exercise.name) }?.rule ?: plan.progression
            if (rule.kind == ProgressionKind.None || !exercise.snapshot.tracking.hasReps ||
                !exercise.snapshot.tracking.hasWeight && rule.kind != ProgressionKind.Manual) return@mapNotNull null
            val working = exercise.sets.filter { it.kind != SetKind.Warmup }
            val completed = working.filter { it.completed }
            val dayIndex = plan.days.indexOfFirst { it.id == session.planDayId }
            val later = plan.days.drop(dayIndex + 1).filter { day ->
                val template = templates.firstOrNull { it.id == day.template.id }
                template?.blocks?.any { it.exercise.id == exercise.exercise.id } ?: templates.isEmpty()
            }
            val nextWeek = (session.planWeek ?: 1) + if (later.isEmpty()) 1 else 0
            if (nextWeek > plan.weeks) return@mapNotNull null
            if (rule.frequency == ProgressionFrequency.Week && later.isNotEmpty()) return@mapNotNull null
            val allHit = working.isNotEmpty() && completed.size == working.size && working.all { s ->
                (s.reps ?: 0) >= (if (rule.kind == ProgressionKind.Double) rule.repMin else s.target.reps ?: s.target.repMax ?: s.target.repMin ?: 1)
            }
            val priorMisses = history.filter { it.status == SessionStatus.Finished && it.planId == plan.id &&
                (it.endedAt == null || session.endedAt == null || it.endedAt <= session.endedAt) }
                .sortedByDescending { it.endedAt }.mapNotNull { it.exercises.firstOrNull { e -> e.exercise.id == exercise.exercise.id } }
                .takeWhile { e -> e.sets.filter { it.kind != SetKind.Warmup }.let { ss -> ss.isEmpty() || ss.any { !it.completed || (it.reps ?: 0) < (if (rule.kind == ProgressionKind.Double) rule.repMin else it.target.reps ?: it.target.repMax ?: it.target.repMin ?: 1) } } }.size
            val forcedDeload = plan.deloadEveryWeeks?.let { nextWeek > 1 && nextWeek % it == 0 } == true
            val missDeload = !allHit && priorMisses + 1 >= rule.missesBeforeDeload
            var reason = "repeat"
            val targets = exercise.sets.map { set ->
                if (set.kind == SetKind.Warmup) return@map set.target
                val currentWeight = set.weight ?: set.target.load?.takeIf { it.kind == LoadKind.Absolute }?.let { mass(it.amount, it.unit, set.unit) }
                var weight = currentWeight
                var reps = set.target.reps ?: set.target.repMin ?: set.reps
                if (rule.kind == ProgressionKind.Manual) {
                    val row = rule.manualWeeks.firstOrNull { it.week == nextWeek } ?: return@map set.target
                    weight = row.weight?.let { mass(it, row.unit, set.unit) } ?: weight; reps = row.reps ?: reps; reason = "manual"
                } else if (forcedDeload || missDeload) {
                    val percent = if (forcedDeload) plan.deloadPercent else rule.deloadPercent
                    weight = weight?.times(1 - percent / 100); reason = "deload"
                } else if (rule.kind == ProgressionKind.Linear && allHit) {
                    weight = weight?.plus(mass(rule.increment, rule.unit, set.unit)); reason = "increase"
                } else if (rule.kind == ProgressionKind.Double && completed.size == working.size && working.isNotEmpty()) {
                    if (working.all { (it.reps ?: 0) >= rule.repMax }) {
                        weight = weight?.plus(mass(rule.increment, rule.unit, set.unit)); reps = rule.repMin; reason = "increase"
                    } else {
                        reps = ((set.reps ?: rule.repMin) + 1).coerceAtMost(rule.repMax); reason = "reps"
                    }
                }
                set.target.copy(reps = reps, load = weight?.let { LoadTarget(rounded(it, exercise.snapshot.increment), set.unit) })
            }
            ProgressionSuggestion(exercise.exercise.id, targets, reason)
        }

    fun warmups(weight: Double, unit: MassUnit, increment: Double): List<SetTarget> {
        require(weight.isFinite() && weight >= 0 && increment > 0)
        return listOf(0.4 to 8, 0.6 to 5, 0.8 to 3).map { (factor, reps) ->
            SetTarget(id = Ids.newId(), kind = SetKind.Warmup, reps = reps, load = LoadTarget(rounded(weight * factor, increment), unit))
        }.distinctBy { it.load?.amount }.filter { (it.load?.amount ?: 0.0) < weight }
    }
    fun plates(target: Double, bar: Double, available: Map<Double, Int>): PlateResult {
        require(target.isFinite() && bar.isFinite() && target >= bar && bar >= 0)
        require(available.all { (weight, count) -> weight.isFinite() && weight > 0 && count >= 0 })
        // Dynamic programming in grams handles non-canonical plate sets where greedy selection fails.
        val goal = ((target - bar) * 500).roundToInt()
        require(goal <= 1_000_000) { "Load is too large" }
        val reachable = mutableMapOf(0 to emptyList<Double>())
        for ((weight, count) in available.entries.sortedByDescending { it.key }) repeat((count / 2).coerceAtMost(100)) {
            val grams = (weight * 1000).roundToInt().coerceAtLeast(1)
            reachable.toList().forEach { (sum, plates) ->
                val next = sum + grams
                if (next <= goal && (reachable[next] == null || reachable[next]!!.size > plates.size + 1)) reachable[next] = plates + weight
            }
        }
        val best = reachable.keys.maxOrNull() ?: 0
        val achieved = bar + best / 500.0
        return PlateResult(reachable.getValue(best).sortedDescending(), achieved, (target - achieved).coerceAtLeast(0.0))
    }
    fun toTemplate(session: WorkoutSessionDocument, id: String? = null) = WorkoutTemplateDocument(
        id = id, title = session.title, tags = session.tags, notes = session.notes,
        blocks = session.exercises.map { e -> WorkoutBlock(Ids.newId(), e.exercise, e.supersetGroup, e.restSeconds, e.notes,
            e.sets.map { s -> s.target.copy(id = Ids.newId(), kind = s.kind, reps = s.reps,
                load = s.weight?.let { LoadTarget(it, s.unit) }, seconds = s.seconds, metres = s.metres, customValues = s.customValues) }) })

    fun strengthPrs(session: WorkoutSessionDocument, history: List<WorkoutSessionDocument>, formula: OneRmFormula = OneRmFormula.Epley): Map<String, Set<String>> =
        session.exercises.filter { it.snapshot.tracking == TrackingMode.WeightReps }.associate { e ->
            val sets = e.sets.filter { it.completed && it.kind != SetKind.Warmup && validateSet(it, e.snapshot.tracking) == null }
            val old = history.filter { it.id != session.id && it.status == SessionStatus.Finished &&
                (it.endedAt == null || session.endedAt == null || it.endedAt <= session.endedAt) }.flatMap { it.exercises }
                .filter { it.exercise.id == e.exercise.id }.flatMap { it.sets }.filter { it.completed && it.kind != SetKind.Warmup }
            val prs = buildSet {
                if (sets.any { s -> kg(s.weight!!, s.unit) > (old.maxOfOrNull { kg(it.weight ?: 0.0, it.unit) } ?: 0.0) }) add("weight")
                if (sets.any { s -> (e1rm(kg(s.weight!!, s.unit), s.reps!!, formula) ?: 0.0) >
                        (old.maxOfOrNull { e1rm(kg(it.weight ?: 0.0, it.unit), it.reps ?: 0, formula) ?: 0.0 } ?: 0.0) }) add("e1rm")
                if (sets.any { s -> kg(s.weight!!, s.unit) * s.reps!! >
                        (old.maxOfOrNull { kg(it.weight ?: 0.0, it.unit) * (it.reps ?: 0) } ?: 0.0) }) add("volume")
            }
            (e.exercise.id ?: e.exercise.name) to prs
        }.filterValues { it.isNotEmpty() }
}

fun FtmDocument.normalisedTraining(): FtmDocument {
    fun target(t: SetTarget) = t.copy(id = t.id ?: Ids.newId())
    return when (this) {
        is WorkoutTemplateDocument -> copy(blocks = blocks.map { it.copy(id = it.id ?: Ids.newId(), sets = it.sets.map(::target)) })
        is WorkoutSessionDocument -> copy(exercises = exercises.map { it.copy(id = it.id ?: Ids.newId(),
            sets = it.sets.map { s -> s.copy(id = s.id ?: Ids.newId(), target = target(s.target)) }) })
        is TrainingPlanDocument -> copy(days = days.map { it.copy(id = it.id ?: Ids.newId()) },
            overrides = overrides.map { it.copy(sets = it.sets.map(::target)) })
        else -> this
    }
}

fun FtmDocument.trainingDependencies(): Set<String> = when (this) {
    is WorkoutTemplateDocument -> blocks.mapNotNull { it.exercise.id }.toSet()
    is WorkoutSessionDocument -> (exercises.mapNotNull { it.exercise.id } + listOfNotNull(templateId, planId)).toSet()
    is TrainingPlanDocument -> (days.mapNotNull { it.template.id } + exerciseRules.mapNotNull { it.exercise.id } + overrides.mapNotNull { it.exercise.id }).toSet()
    else -> emptySet()
}
