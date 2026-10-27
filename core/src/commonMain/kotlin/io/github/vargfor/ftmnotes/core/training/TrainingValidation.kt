package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.format.ReadIssue
import io.github.vargfor.ftmnotes.core.model.*
import kotlinx.serialization.json.*

object TrainingValidation {
    fun issues(document: FtmDocument, path: String = "$"): List<ReadIssue> = buildList {
        fun bad(at: String, message: String) { add(ReadIssue(ReadIssue.Severity.Error, path + at, message)) }
        fun number(value: Double?, at: String, positive: Boolean = false) {
            if (value != null && (!value.isFinite() || if (positive) value <= 0 else value < 0)) bad(at, "Expected a finite " + if (positive) "positive number" else "nonnegative number")
        }
        fun rest(value: Int?, at: String) { if (value != null && value !in 0..604800) bad(at, "Rest must be between 0 and 604800 seconds") }
        fun target(t: SetTarget, at: String) {
            if (t.reps != null && t.reps <= 0) bad(at + ".reps", "Reps must be positive")
            if (t.repMin != null && t.repMin <= 0 || t.repMax != null && t.repMax <= 0 ||
                t.repMin != null && t.repMax != null && t.repMax < t.repMin) bad(at, "Invalid rep range")
            number(t.seconds, at + ".seconds", true); number(t.metres, at + ".metres", true)
            t.load?.let { load ->
                number(load.amount, at + ".load.amount")
                if (load.kind == LoadKind.Rpe && load.amount !in 1.0..10.0 || load.kind == LoadKind.Rir && load.amount !in 0.0..10.0)
                    bad(at + ".load.amount", "RPE must be 1–10; RIR must be 0–10")
                if (load.kind in listOf(LoadKind.PercentOneRm, LoadKind.PercentTrainingMax) && load.amount > 200)
                    bad(at + ".load.amount", "Percentage exceeds 200")
            }
        }
        fun rule(r: ProgressionRule, at: String) {
            number(r.increment, at + ".increment", true)
            if (r.missesBeforeDeload < 1 || r.deloadPercent !in 0.0..100.0 || r.repMin < 1 || r.repMax < r.repMin) bad(at, "Invalid progression rule")
            if (r.manualWeeks.map { it.week }.distinct().size != r.manualWeeks.size) bad(at + ".manualWeeks", "Weeks must be unique")
            r.manualWeeks.forEachIndexed { i, w ->
                if (w.week < 1 || w.reps != null && w.reps < 1) bad(at + ".manualWeeks[" + i + "]", "Invalid weekly target")
                number(w.weight, at + ".manualWeeks[" + i + "].weight")
            }
        }
        fun unique(ids: List<String?>, at: String) {
            val present = ids.filterNotNull()
            if (present.any { it.isBlank() } || present.distinct().size != present.size) bad(at, "Item IDs must be nonblank and unique")
        }
        when (document) {
            is ExerciseDocument -> {
                number(document.increment, ".increment", true); rest(document.defaultRestSeconds, ".defaultRestSeconds")
                if (document.fields.any { it.key.isBlank() } || document.fields.map { it.key }.distinct().size != document.fields.size) bad(".fields", "Custom field keys must be unique and nonblank")
            }
            is WorkoutTemplateDocument -> {
                unique(document.blocks.map { it.id }, ".blocks")
                document.blocks.forEachIndexed { i, b ->
                    val at = ".blocks[" + i + "]"
                    if (b.exercise.id.isNullOrBlank() && b.exercise.name.isBlank()) bad(at + ".exercise", "Choose an exercise")
                    rest(b.restSeconds, at + ".restSeconds"); unique(b.sets.map { it.id }, at + ".sets")
                    b.sets.forEachIndexed { j, t -> target(t, at + ".sets[" + j + "]") }
                }
            }
            is WorkoutSessionDocument -> {
                number(document.bodyweight, ".bodyweight", true)
                if (document.rating != null && document.rating !in 0..5) bad(".rating", "Rating must be 0–5")
                if (document.startedAt != null && document.endedAt != null && document.endedAt < document.startedAt) bad(".endedAt", "End must follow start")
                if (document.status == SessionStatus.Finished && (document.startedAt == null || document.endedAt == null)) bad(".status", "Finished sessions need start and end times")
                unique(document.exercises.map { it.id }, ".exercises")
                unique(document.exercises.flatMap { e -> e.sets.map { it.id } }, ".exercises.sets")
                document.exercises.forEachIndexed { i, e ->
                    val at = ".exercises[" + i + "]"
                    number(e.snapshot.increment, at + ".snapshot.increment", true)
                    if (e.snapshot.fields.map { it.key }.distinct().size != e.snapshot.fields.size || e.snapshot.fields.any { it.key.isBlank() }) bad(at + ".snapshot.fields", "Custom field keys must be unique and nonblank")
                    rest(e.restSeconds, at + ".restSeconds"); unique(e.sets.map { it.id }, at + ".sets")
                    e.sets.forEachIndexed { j, s ->
                        val p = at + ".sets[" + j + "]"
                        target(s.target, p + ".target"); number(s.weight, p + ".weight")
                        if (s.reps != null && s.reps < 0) bad(p + ".reps", "Reps cannot be negative")
                        number(s.seconds, p + ".seconds"); number(s.metres, p + ".metres")
                        if (s.rpe != null && (!s.rpe.isFinite() || s.rpe !in 1.0..10.0)) bad(p + ".rpe", "RPE must be between 1 and 10")
                        if (s.rir != null && (!s.rir.isFinite() || s.rir !in 0.0..10.0)) bad(p + ".rir", "RIR must be between 0 and 10")
                        e.snapshot.fields.forEach { field -> s.customValues[field.key]?.let { value ->
                            val primitive = value as? JsonPrimitive
                            val valid = when(field.kind) {
                                FieldKind.Number -> primitive != null && !primitive.isString && primitive.doubleOrNull?.isFinite() == true
                                FieldKind.Boolean -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
                                FieldKind.Text -> primitive?.isString == true
                            }
                            if (!valid) bad(p + ".customValues." + field.key, "Invalid custom field value")
                        } }
                        if (s.completed) TrainingEngine.validateSet(s, e.snapshot.tracking)?.let { bad(p + "." + it, "Completed set has an invalid or missing value") }
                    }
                }
            }
            is TrainingPlanDocument -> {
                if (document.weeks !in 1..260) bad(".weeks", "Plan length must be 1–260 weeks")
                if (document.deloadEveryWeeks != null && document.deloadEveryWeeks < 1 || document.deloadPercent !in 0.0..100.0) bad(".deloadEveryWeeks", "Invalid deload")
                unique(document.days.map { it.id }, ".days")
                document.days.forEachIndexed { i, d -> if (d.template.id.isNullOrBlank() && d.template.name.isBlank()) bad(".days[" + i + "].template", "Choose a routine") }
                rule(document.progression, ".progression")
                document.exerciseRules.forEachIndexed { i, r -> rule(r.rule, ".exerciseRules[" + i + "].rule") }
                document.overrides.forEachIndexed { i, o ->
                    if (o.dayId != null && document.days.none { it.id == o.dayId }) bad(".overrides[" + i + "].dayId", "Day is outside the plan")
                    if (o.week !in 1..document.weeks) bad(".overrides[" + i + "].week", "Week is outside the plan")
                    o.sets.forEachIndexed { j, t -> target(t, ".overrides[" + i + "].sets[" + j + "]") }
                }
            }
            else -> Unit
        }
    }

    fun normalise(fields: MutableMap<String, JsonElement>, type: DocumentType, path: String, issues: MutableList<ReadIssue>) {
        if (type !in setOf(DocumentType.Exercise, DocumentType.WorkoutTemplate, DocumentType.WorkoutSession, DocumentType.TrainingPlan)) return
        val integers = setOf("reps", "repMin", "repMax", "week", "weeks", "restSeconds", "defaultRestSeconds", "missesBeforeDeload", "deloadEveryWeeks", "rating", "planWeek")
        val numbers = integers + setOf("weight", "amount", "increment", "seconds", "metres", "bodyweight", "rpe", "rir", "deloadPercent")
        val booleans = setOf("completed", "pinned", "archived")
        fun visit(value: JsonElement, at: String, key: String = ""): JsonElement {
            if (key == "customValues") return value
            if (value is JsonArray) return JsonArray(value.mapIndexed { i, v -> visit(v, at + "[" + i + "]") })
            if (value is JsonObject) {
                val result = value.toMutableMap()
                if ("routine" in result && "template" !in result) result["template"] = result.remove("routine")!!
                val reps = (result["reps"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                Regex("^(\\d+)\\s*[-–]\\s*(\\d+)$").matchEntire(reps.orEmpty())?.let {
                    result.remove("reps"); result["repMin"] = JsonPrimitive(it.groupValues[1].toInt()); result["repMax"] = JsonPrimitive(it.groupValues[2].toInt())
                }
                return JsonObject(result.mapValues { (k, v) -> visit(v, at + "." + k, k) })
            }
            if (key == "load" && value is JsonPrimitive && !value.isString && value.doubleOrNull != null)
                return buildJsonObject { put("amount", value) }
            if (value !is JsonPrimitive || !value.isString) return value
            val raw = value.content.trim()
            val fixed = when {
                key == "exercise" || key == "template" -> buildJsonObject { put("name", raw) }
                key == "load" -> raw.replace(',', '.').toDoubleOrNull()?.let { buildJsonObject { put("amount", it) } }
                key in integers -> raw.toIntOrNull()?.let(::JsonPrimitive)
                key in numbers -> raw.replace(',', '.').toDoubleOrNull()?.let(::JsonPrimitive)
                key in booleans -> raw.lowercase().toBooleanStrictOrNull()?.let(::JsonPrimitive)
                key in setOf("unit", "unitOverride", "bodyweightUnit") -> when (raw.lowercase()) {
                    "lb", "lbs", "pounds", "pound" -> JsonPrimitive("lb")
                    "kg", "kgs", "kilograms", "kilogram", "kilo" -> JsonPrimitive("kg")
                    else -> null
                }
                key == "tracking" -> when (raw.lowercase()) {
                    "weight", "weighted", "weight/reps" -> JsonPrimitive("weight_reps")
                    "bodyweight" -> JsonPrimitive("bodyweight_reps")
                    "time" -> JsonPrimitive("duration")
                    "time_distance", "time+distance" -> JsonPrimitive("duration_distance")
                    else -> null
                }
                key == "status" && raw.lowercase() == "completed" -> JsonPrimitive("finished")
                else -> null
            }
            if (fixed != null && fixed != value) issues += ReadIssue(ReadIssue.Severity.Info, at, "Normalised Training value")
            return fixed ?: value
        }
        if (type == DocumentType.WorkoutTemplate && "exercises" in fields && "blocks" !in fields) fields["blocks"] = fields.remove("exercises")!!
        var normal = visit(JsonObject(fields), path).jsonObject
        if (type == DocumentType.WorkoutSession) {
            normal = JsonObject(normal.toMutableMap().apply {
                (normal["exercises"] as? JsonArray)?.let { entries -> put("exercises", JsonArray(entries.map { raw ->
                    if (raw !is JsonObject || raw["snapshot"] != null) raw else {
                        val ref = raw["exercise"] as? JsonObject
                        val sets = (raw["sets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                        val tracking = ref?.get("tracking") ?: JsonPrimitive(when {
                            sets.any { it["seconds"] != null && it["metres"] != null } -> "duration_distance"
                            sets.any { it["seconds"] != null } -> "duration"
                            sets.any { it["metres"] != null } -> "distance"
                            sets.any { it["weight"] != null } -> "weight_reps"
                            else -> "bodyweight_reps"
                        })
                        JsonObject(raw + ("snapshot" to buildJsonObject { put("name", ref?.get("name") ?: JsonPrimitive("")); put("tracking", tracking) }))
                    }
                })) }
            })
        }
        fields.clear(); fields.putAll(normal)
    }
}
