package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.backup.importFingerprint
import io.github.vargfor.ftmnotes.core.format.ReadIssue
import io.github.vargfor.ftmnotes.core.model.*
import kotlinx.serialization.Serializable

@Serializable data class ReferenceCandidate(val id: String, val name: String, val detail: String = "")
@Serializable data class ReferenceQuestion(val key: String, val ownerId: String, val name: String, val candidates: List<ReferenceCandidate>)
data class TrainingResolution(val documents: List<FtmDocument>, val issues: List<ReadIssue>, val questions: List<ReferenceQuestion>)

object TrainingBundles {
    fun resolve(input: List<FtmDocument>, existing: List<FtmDocument>, choices: Map<String, String> = emptyMap(), namespace: String? = null): TrainingResolution {
        val issues = mutableListOf<ReadIssue>()
        val questions = mutableListOf<ReferenceQuestion>()
        val occupied = existing.mapNotNull { it.id }.toMutableSet()
        val remap = mutableMapOf<String, String>()
        val ids = input.mapNotNull { it.id }
        if (ids.distinct().size != ids.size) issues += ReadIssue(ReadIssue.Severity.Error, "$", "Duplicate document IDs in the bundle")
        val prepared = input.mapIndexed { index, doc ->
            val duplicate = existing.firstOrNull { it.documentType == doc.documentType && it.importFingerprint() == doc.importFingerprint() }
            val reusable = if (doc is ExerciseDocument) existing.filterIsInstance<ExerciseDocument>().singleOrNull {
                TrainingEngine.matches(ExerciseRef(name = doc.title, tracking = doc.tracking, equipment = doc.equipment), it) && it.fields == doc.fields
            } else null
            val id = (duplicate ?: reusable)?.id ?: doc.id?.takeUnless { it in occupied } ?: namespace?.let { Ids.seeded("training-import", it + "/" + index + "/" + doc.id) } ?: Ids.newId()
            doc.id?.let { remap[it] = id }; occupied += id
            doc.withMeta(id, doc.createdAt, doc.updatedAt).normalisedTraining()
        }.toMutableList()
        val reused = prepared.mapNotNull { doc -> existing.firstOrNull { it.id == doc.id } }.associateBy { it.id }
        fun resolveExercise(ref: ExerciseRef, owner: String, key: String, inferred: TrackingMode = TrackingMode.WeightReps): ExerciseRef {
            val catalog = (prepared.filterIsInstance<ExerciseDocument>() + existing.filterIsInstance<ExerciseDocument>()).distinctBy { it.id }
            val requested = choices[key]?.takeUnless { it == "@new" } ?: ref.id?.let { remap[it] ?: it }
            val byId = requested?.let { id -> catalog.firstOrNull { it.id == id } }
            val exact = catalog.filter { TrainingEngine.matches(ref.copy(id = null), it) }
            val matched = if (choices[key] == "@new") null else byId ?: exact.singleOrNull()
            if (matched != null) return ExerciseRef(matched.id, matched.title, matched.tracking, matched.equipment)
            val candidates = if (exact.size > 1) exact else catalog.filter {
                val a = TrainingEngine.key(it.title); val b = TrainingEngine.key(ref.name)
                b.isNotEmpty() && (a.contains(b) || b.contains(a) || a.split(' ').intersect(b.split(' ').toSet()).size >= 2)
            }.take(12)
            if (ref.name.isBlank()) {
                issues += ReadIssue(ReadIssue.Severity.Error, key, "Exercise reference is unresolved")
                return ref
            }
            if (candidates.isNotEmpty() && choices[key] != "@new") {
                questions += ReferenceQuestion(key, owner, ref.name, candidates.map { ReferenceCandidate(it.id!!, it.title, it.equipment + " · " + it.tracking.name) })
                return ref.copy(id = null)
            }
            val made = ExerciseDocument(id = namespace?.let { Ids.seeded("training-import-exercise", it + "/" + key) } ?: Ids.newId(), title = ref.name, tracking = ref.tracking ?: inferred, equipment = ref.equipment.orEmpty())
            prepared += made
            issues += ReadIssue(ReadIssue.Severity.Info, key, "A new exercise will be created: " + made.title)
            return ExerciseRef(made.id, made.title, made.tracking, made.equipment)
        }
        val resolvedTemplates = prepared.filterIsInstance<WorkoutTemplateDocument>().associate { doc ->
            doc.id!! to doc.copy(blocks = doc.blocks.mapIndexed { i, b ->
                val inferred = when { b.sets.any { it.metres != null && it.seconds != null } -> TrackingMode.DurationDistance
                    b.sets.any { it.metres != null } -> TrackingMode.Distance; b.sets.any { it.seconds != null } -> TrackingMode.Duration
                    b.sets.any { it.load != null } -> TrackingMode.WeightReps; else -> b.exercise.tracking ?: TrackingMode.WeightReps }
                b.copy(exercise = resolveExercise(b.exercise, doc.id, doc.id + "/blocks/" + i, inferred))
            })
        }
        fun template(ref: TemplateRef, owner: String, key: String): TemplateRef {
            val catalog = (resolvedTemplates.values + existing.filterIsInstance<WorkoutTemplateDocument>()).distinctBy { it.id }
            val id = choices[key] ?: ref.id?.let { remap[it] ?: it }
            val found = catalog.firstOrNull { id != null && it.id == id } ?: catalog.filter { TrainingEngine.key(it.title) == TrainingEngine.key(ref.name) }.singleOrNull()
            if (found != null) return TemplateRef(found.id, found.title)
            val options = catalog.filter { TrainingEngine.key(it.title).contains(TrainingEngine.key(ref.name)) }.take(12)
            if (options.isNotEmpty()) questions += ReferenceQuestion(key, owner, ref.name, options.map { ReferenceCandidate(it.id!!, it.title) })
            else issues += ReadIssue(ReadIssue.Severity.Error, key, "Routine reference is unresolved: " + ref.name)
            return ref.copy(id = null)
        }
        val result = prepared.toList().map { doc ->
            reused[doc.id] ?: when (doc) {
                is WorkoutTemplateDocument -> resolvedTemplates.getValue(doc.id!!)
                is WorkoutSessionDocument -> {
                    if (doc.status != SessionStatus.Finished) issues += ReadIssue(ReadIssue.Severity.Error, doc.id!!, "Only finished workout history can be imported; live workouts are recovered from ZIP backups")
                    doc.copy(templateId = doc.templateId?.let { remap[it] ?: it }, planId = doc.planId?.let { remap[it] ?: it },
                        exercises = doc.exercises.mapIndexed { i, e ->
                            val ref = resolveExercise(e.exercise.copy(name = e.exercise.name.ifBlank { e.snapshot.name }), doc.id!!, doc.id + "/exercises/" + i, e.snapshot.tracking)
                            val original = (prepared + existing).filterIsInstance<ExerciseDocument>().firstOrNull { it.id == ref.id }
                            e.copy(exercise = ref, snapshot = if (e.snapshot.name.isNotBlank()) e.snapshot else original?.let { ExerciseSnapshot(it.title, it.tracking, it.equipment, it.muscleGroups, it.fields, it.increment) } ?: e.snapshot)
                        })
                }
                is TrainingPlanDocument -> doc.copy(days = doc.days.mapIndexed { i, d -> d.copy(template = template(d.template, doc.id!!, doc.id + "/days/" + i)) },
                    exerciseRules = doc.exerciseRules.mapIndexed { i, r -> r.copy(exercise = resolveExercise(r.exercise, doc.id!!, doc.id + "/rules/" + i)) },
                    overrides = doc.overrides.mapIndexed { i, o -> o.copy(exercise = resolveExercise(o.exercise, doc.id!!, doc.id + "/overrides/" + i)) })
                else -> doc
            }
        }.toMutableList()
        // New exercises can be discovered while resolving session history or plan rules.
        prepared.filterIsInstance<ExerciseDocument>().filter { e -> result.none { it.id == e.id } }.forEach { result += it }
        return TrainingResolution(result.distinctBy { it.id }, issues, questions)
    }

    /** Historical snapshots keep content exports usable after a linked catalog item is purged. */
    fun exportClosure(selected: Set<String>, documents: List<FtmDocument>): List<FtmDocument> {
        val all = documents.toMutableList()
        val known = all.mapNotNull { it.id }.toMutableSet()
        fun exercise(ref: ExerciseRef, snapshot: ExerciseSnapshot? = null) {
            val id = ref.id ?: return
            if (!known.add(id)) return
            all += ExerciseDocument(id = id, title = snapshot?.name?.ifBlank { ref.name } ?: ref.name,
                tracking = snapshot?.tracking ?: ref.tracking ?: TrackingMode.WeightReps,
                equipment = snapshot?.equipment ?: ref.equipment.orEmpty(), fields = snapshot?.fields.orEmpty(),
                muscleGroups = snapshot?.muscleGroups.orEmpty(), increment = snapshot?.increment ?: 2.5)
        }
        all.toList().filterIsInstance<WorkoutSessionDocument>().forEach { session -> session.exercises.forEach { exercise(it.exercise, it.snapshot) } }
        all.toList().filterIsInstance<WorkoutTemplateDocument>().forEach { template -> template.blocks.forEach { exercise(it.exercise) } }
        val ready = all.map { doc -> if (doc is WorkoutSessionDocument) doc.copy(
            templateId = doc.templateId?.takeIf { id -> all.any { it.id == id && it is WorkoutTemplateDocument } },
            planId = doc.planId?.takeIf { id -> all.any { it.id == id && it is TrainingPlanDocument } },
            planDayId = doc.planDayId.takeIf { doc.planId in known }) else doc }
        return closure(selected, ready)
    }

    fun closure(selected: Set<String>, documents: List<FtmDocument>): List<FtmDocument> {
        val byId = documents.associateBy { it.id }
        val needed = selected.toMutableSet()
        fun add(id: String) { byId[id]?.trainingDependencies()?.forEach { if (needed.add(it)) add(it) } }
        selected.forEach(::add)
        return documents.filter { it.id in needed }
    }
    fun validateReferences(documents: List<FtmDocument>, existing: List<FtmDocument>): List<String> {
        val all = (documents + existing).associateBy { it.id }
        return buildList {
            documents.forEach { doc ->
                doc.trainingDependencies().filter { it !in all }.forEach { add(doc.title + ": missing dependency " + it) }
                fun expect(id: String?, type: DocumentType) { if (id != null && all[id]?.documentType != type) add(doc.title + ": invalid " + type.wire + " reference " + id) }
                when (doc) {
                    is WorkoutTemplateDocument -> doc.blocks.forEach { expect(it.exercise.id, DocumentType.Exercise) }
                    is TrainingPlanDocument -> {
                        doc.days.forEach { expect(it.template.id, DocumentType.WorkoutTemplate) }
                        (doc.exerciseRules.map { it.exercise } + doc.overrides.map { it.exercise }).forEach { expect(it.id, DocumentType.Exercise) }
                    }
                    is WorkoutSessionDocument -> { expect(doc.templateId, DocumentType.WorkoutTemplate); expect(doc.planId, DocumentType.TrainingPlan); doc.exercises.forEach { expect(it.exercise.id, DocumentType.Exercise) } }
                    else -> Unit
                }
                if (doc is WorkoutTemplateDocument && doc.blocks.any { it.exercise.id == null }) add(doc.title + ": unresolved exercise")
                if (doc is TrainingPlanDocument && (doc.days.any { it.template.id == null } || doc.exerciseRules.any { it.exercise.id == null } || doc.overrides.any { it.exercise.id == null })) add(doc.title + ": unresolved plan reference")
                if (doc is WorkoutSessionDocument && (doc.status != SessionStatus.Finished || doc.exercises.any { it.exercise.id == null })) add(doc.title + ": invalid session import")
            }
        }
    }
}
