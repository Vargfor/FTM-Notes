package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.format.ReadIssue
import io.github.vargfor.ftmnotes.core.model.*
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class StrongImportResult(val documents: List<FtmDocument>, val issues: List<ReadIssue>)
object StrongCsv {
    fun parse(text: String, weightUnit: MassUnit = MassUnit.Kg, distanceMultiplier: Double = 1.0,
              timeZone: TimeZone = TimeZone.currentSystemDefault()): StrongImportResult {
        val issues = mutableListOf<ReadIssue>()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> csv(text.lineSequence().firstOrNull().orEmpty(), d).firstOrNull()?.size ?: 0 }
        val rows = csv(text.removePrefix("\uFEFF"), delimiter)
        if (rows.isEmpty()) return StrongImportResult(emptyList(), listOf(ReadIssue(ReadIssue.Severity.Error, "$", "CSV is empty")))
        val headers = rows.first().map { TrainingEngine.key(it).replace(" ", "") }
        fun column(vararg names: String) = headers.indexOfFirst { h -> names.any { h == it || h.removePrefix(it).takeIf { suffix -> suffix != h } in setOf("kg", "kgs", "lb", "lbs", "km", "m", "mi", "mile", "miles", "meters", "metres") } }
        val dateCol = column("date", "starttime", "startedat")
        val exerciseCol = column("exercisename", "exercise")
        val workoutCol = column("workoutname", "workout", "title")
        val weightCol = column("weight", "weightkg", "weightlbs")
        val repsCol = column("reps", "repetitions")
        val secondsCol = column("seconds", "durationseconds")
        val distanceCol = column("distance", "distancemeters", "distancemetres")
        val durationCol = column("duration", "workoutduration")
        val rpeCol = column("rpe")
        val noteCol = column("notes", "exercisenotes")
        val workoutNoteCol = column("workoutnotes")
        val typeCol = column("settype", "setkind", "setorder")
        require(dateCol >= 0 && exerciseCol >= 0) { "CSV needs Date and Exercise Name columns" }
        val detectedUnit = headers.getOrNull(weightCol)?.let { if (it.contains("lb")) MassUnit.Lb else if (it.contains("kg")) MassUnit.Kg else null }
        val distanceScale = headers.getOrNull(distanceCol)?.let { when { it.contains("km") -> 1000.0; it.contains("mile") || it.endsWith("mi") -> 1609.344; it.contains("meter") || it.contains("metre") || it.endsWith("m") -> 1.0; else -> distanceMultiplier } } ?: distanceMultiplier
        if (weightCol >= 0 && detectedUnit == null) issues += ReadIssue(ReadIssue.Severity.Warning, "$.weight", "No weight unit in the header; using the selected unit: " + weightUnit.name)
        if (distanceCol >= 0) issues += ReadIssue(ReadIssue.Severity.Info, "$.distance", "Distance conversion: one CSV unit = " + distanceScale + " metres")
        val exercises = linkedMapOf<String, ExerciseDocument>()
        val sessions = linkedMapOf<String, WorkoutSessionDocument>()
        rows.drop(1).forEachIndexed { index, row ->
            val path = "$.rows[" + (index + 2) + "]"
            if (row.all { it.isBlank() }) return@forEachIndexed
            fun value(col: Int) = row.getOrNull(col)?.trim().orEmpty()
            fun number(col: Int) = value(col).replace(',', '.').takeIf { it.isNotBlank() }?.let {
                it.toDoubleOrNull() ?: error("Invalid number: " + it)
            }
            try {
                val started = parseDate(value(dateCol), timeZone)
                val name = value(exerciseCol)
                require(name.isNotBlank()) { "Exercise name is empty" }
                val weight = number(weightCol); val reps = number(repsCol)?.let { require(it % 1 == 0.0); it.toInt() }
                val seconds = number(secondsCol); val metres = number(distanceCol)?.times(distanceScale)
                val tracking = when {
                    metres != null && seconds != null && metres > 0 && seconds > 0 -> TrackingMode.DurationDistance
                    metres != null && metres > 0 -> TrackingMode.Distance
                    seconds != null && seconds > 0 && (reps == null || reps == 0) -> TrackingMode.Duration
                    weight != null && weight > 0 -> TrackingMode.WeightReps
                    else -> TrackingMode.BodyweightReps
                }
                val exerciseKey = TrainingEngine.key(name) + "/" + tracking.name
                val exercise = exercises.getOrPut(exerciseKey) { ExerciseDocument(Ids.newId(), name, tracking = tracking) }
                val title = value(workoutCol).ifBlank { "Imported workout" }
                val sessionKey = started.toString() + "/" + title
                val duration = parseDuration(value(durationCol))
                val session = sessions.getOrPut(sessionKey) { WorkoutSessionDocument(id = Ids.newId(), title = title,
                    status = SessionStatus.Finished, startedAt = started, endedAt = started + duration.seconds, notes = value(workoutNoteCol)) }
                val kind = when (value(typeCol).lowercase()) { "w", "warmup", "warm up" -> SetKind.Warmup; "d", "drop" -> SetKind.Drop; "f", "failure" -> SetKind.Failure; else -> SetKind.Working }
                val set = PerformedSet(id = Ids.newId(), kind = kind, weight = weight ?: if (tracking == TrackingMode.BodyweightReps) 0.0 else null,
                    unit = detectedUnit ?: weightUnit, reps = reps?.takeIf { it > 0 }, seconds = seconds?.takeIf { it > 0 },
                    metres = metres?.takeIf { it > 0 }, rpe = number(rpeCol), completed = true)
                require(TrainingEngine.validateSet(set, tracking) == null) { "Invalid completed set" }
                val position = session.exercises.indexOfFirst { it.exercise.id == exercise.id }
                val entry = session.exercises.getOrNull(position) ?: SessionExercise(id = Ids.newId(), exercise = ExerciseRef(exercise.id, name, tracking),
                    snapshot = ExerciseSnapshot(name, tracking), notes = value(noteCol))
                val entries = session.exercises.toMutableList()
                val changed = entry.copy(sets = entry.sets + set)
                if (position < 0) entries += changed else entries[position] = changed
                sessions[sessionKey] = session.copy(exercises = entries)
            } catch (e: Exception) {
                issues += ReadIssue(ReadIssue.Severity.Error, path, e.message ?: "Invalid CSV row")
            }
        }
        return StrongImportResult(exercises.values.toList() + sessions.values, issues)
    }

    internal fun csv(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    row += cell.toString(); cell.clear(); rows += row; row = mutableListOf()
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                }
                else -> cell.append(c)
            }; i++
        }
        require(!quoted) { "Unclosed quoted CSV field" }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
    private fun parseDate(raw: String, zone: TimeZone): Instant {
        runCatching { return Instant.parse(raw) }
        val normal = raw.replace(' ', 'T')
        runCatching { return LocalDateTime.parse(normal).toInstant(zone) }
        val parts = Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matchEntire(raw)
            ?: error("Unrecognised date: " + raw)
        val p = parts.groupValues
        return LocalDateTime(p[3].toInt(), p[2].toInt(), p[1].toInt(), p[4].toInt(), p[5].toInt(), p[6].ifBlank { "0" }.toInt()).toInstant(zone)
    }
    private fun parseDuration(raw: String): Long {
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        if (':' in raw) {
            val parts = raw.split(':').map { it.toLong() }
            return parts.fold(0L) { acc, v -> acc * 60 + v }.coerceAtLeast(0)
        }
        return Regex("(\\d+)\\s*(h|m|s)").findAll(raw.lowercase()).sumOf {
            it.groupValues[1].toLong() * when(it.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 }
        }
    }
}
