package io.github.vargfor.ftmnotes.core.model

import io.github.vargfor.ftmnotes.core.format.LenientInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

@Serializable enum class TrackingMode {
    @SerialName("weight_reps") WeightReps, @SerialName("bodyweight_reps") BodyweightReps,
    @SerialName("reps") Reps, @SerialName("duration") Duration,
    @SerialName("distance") Distance, @SerialName("duration_distance") DurationDistance;
    val hasWeight get() = this == WeightReps || this == BodyweightReps
    val hasReps get() = this == WeightReps || this == BodyweightReps || this == Reps
    val hasDuration get() = this == Duration || this == DurationDistance
    val hasDistance get() = this == Distance || this == DurationDistance
}
@Serializable enum class MassUnit(override val wire: String) : io.github.vargfor.ftmnotes.core.settings.WireValue { @SerialName("kg") Kg("kg"), @SerialName("lb") Lb("lb") }
@Serializable enum class SetKind {
    @SerialName("warmup") Warmup, @SerialName("working") Working, @SerialName("drop") Drop,
    @SerialName("failure") Failure, @SerialName("amrap") Amrap
}
@Serializable enum class LoadKind {
    @SerialName("absolute") Absolute, @SerialName("percent_1rm") PercentOneRm,
    @SerialName("percent_tm") PercentTrainingMax, @SerialName("rpe") Rpe, @SerialName("rir") Rir
}
@Serializable enum class FieldKind { @SerialName("number") Number, @SerialName("text") Text, @SerialName("boolean") Boolean }
@Serializable enum class SessionStatus { @SerialName("draft") Draft, @SerialName("in_progress") InProgress, @SerialName("finished") Finished }
@Serializable enum class ProgressionKind { @SerialName("none") None, @SerialName("linear") Linear, @SerialName("double") Double, @SerialName("manual") Manual }
@Serializable enum class ProgressionFrequency { @SerialName("session") Session, @SerialName("week") Week }

@Serializable data class CustomTrainingField(val key: String, val label: String = key, val kind: FieldKind = FieldKind.Number, val unit: String = "")
@Serializable data class ExerciseRef(val id: String? = null, val name: String = "", val tracking: TrackingMode? = null, val equipment: String? = null)
@Serializable data class TemplateRef(val id: String? = null, val name: String = "")
@Serializable data class LoadTarget(val amount: Double = 0.0, val unit: MassUnit = MassUnit.Kg, val kind: LoadKind = LoadKind.Absolute)
@Serializable data class SetTarget(
    val id: String? = null, val kind: SetKind = SetKind.Working,
    val reps: Int? = null, val repMin: Int? = null, val repMax: Int? = null,
    val load: LoadTarget? = null, val seconds: Double? = null, val metres: Double? = null,
    val customValues: Map<String, JsonElement> = emptyMap(),
)
@Serializable data class WorkoutBlock(
    val id: String? = null, val exercise: ExerciseRef = ExerciseRef(),
    val supersetGroup: String? = null, val restSeconds: Int? = null,
    val notes: String = "", val sets: List<SetTarget> = emptyList(),
)
@Serializable data class ExerciseSnapshot(
    val name: String = "", val tracking: TrackingMode = TrackingMode.WeightReps,
    val equipment: String = "", val muscleGroups: List<String> = emptyList(),
    val fields: List<CustomTrainingField> = emptyList(), val increment: Double = 2.5,
)
@Serializable data class PerformedSet(
    val id: String? = null, val target: SetTarget = SetTarget(), val kind: SetKind = target.kind,
    val weight: Double? = null, val unit: MassUnit = MassUnit.Kg, val reps: Int? = null,
    val seconds: Double? = null, val metres: Double? = null, val rpe: Double? = null,
    val rir: Double? = null, val completed: Boolean = false,
    val customValues: Map<String, JsonElement> = emptyMap(),
)
@Serializable data class SessionExercise(
    val id: String? = null, val exercise: ExerciseRef = ExerciseRef(),
    val snapshot: ExerciseSnapshot = ExerciseSnapshot(), val supersetGroup: String? = null,
    val restSeconds: Int = 90, val notes: String = "", val sets: List<PerformedSet> = emptyList(),
)
@Serializable data class PlanDay(val id: String? = null, val name: String = "", val template: TemplateRef = TemplateRef())
@Serializable data class WeekOverride(val week: Int, val dayId: String? = null, val exercise: ExerciseRef = ExerciseRef(), val sets: List<SetTarget> = emptyList())
@Serializable data class WeekTarget(val week: Int, val weight: Double? = null, val reps: Int? = null, val unit: MassUnit = MassUnit.Kg)
@Serializable data class ProgressionRule(
    val kind: ProgressionKind = ProgressionKind.None, val increment: Double = 2.5,
    val unit: MassUnit = MassUnit.Kg, val frequency: ProgressionFrequency = ProgressionFrequency.Session,
    val missesBeforeDeload: Int = 3, val deloadPercent: Double = 10.0,
    val repMin: Int = 8, val repMax: Int = 12, val manualWeeks: List<WeekTarget> = emptyList(),
)
@Serializable data class ExerciseProgression(val exercise: ExerciseRef = ExerciseRef(), val rule: ProgressionRule = ProgressionRule())

@Serializable
@SerialName("exercise")
data class ExerciseDocument(
    override val id: String? = null, override val title: String = "",
    override val tags: List<String> = emptyList(), override val pinned: Boolean = false,
    override val archived: Boolean = false, override val color: String? = null,
    @Serializable(with = LenientInstantSerializer::class) override val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class) override val updatedAt: Instant? = null,
    val aliases: List<String> = emptyList(), val muscleGroups: List<String> = emptyList(),
    val equipment: String = "", val tracking: TrackingMode = TrackingMode.WeightReps,
    val fields: List<CustomTrainingField> = emptyList(), val defaultRestSeconds: Int = 90,
    val increment: Double = 2.5, val unitOverride: MassUnit? = null,
    val notes: String = "", val instructionUrl: String? = null,
    val category: String = "",
) : FtmDocument { override val documentType: DocumentType get() = DocumentType.Exercise }

@Serializable
@SerialName("workout_template")
data class WorkoutTemplateDocument(
    override val id: String? = null, override val title: String = "",
    override val tags: List<String> = emptyList(), override val pinned: Boolean = false,
    override val archived: Boolean = false, override val color: String? = null,
    @Serializable(with = LenientInstantSerializer::class) override val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class) override val updatedAt: Instant? = null,
    val blocks: List<WorkoutBlock> = emptyList(), val notes: String = "",
) : FtmDocument { override val documentType: DocumentType get() = DocumentType.WorkoutTemplate }

@Serializable
@SerialName("workout_session")
data class WorkoutSessionDocument(
    override val id: String? = null, override val title: String = "",
    override val tags: List<String> = emptyList(), override val pinned: Boolean = false,
    override val archived: Boolean = false, override val color: String? = null,
    @Serializable(with = LenientInstantSerializer::class) override val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class) override val updatedAt: Instant? = null,
    val status: SessionStatus = SessionStatus.Draft,
    @Serializable(with = LenientInstantSerializer::class) val startedAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class) val endedAt: Instant? = null,
    val templateId: String? = null, val templateName: String = "",
    val planId: String? = null, val planWeek: Int? = null, val planDayId: String? = null,
    val bodyweight: Double? = null, val bodyweightUnit: MassUnit = MassUnit.Kg,
    val exercises: List<SessionExercise> = emptyList(), val notes: String = "", val rating: Int? = null,
) : FtmDocument { override val documentType: DocumentType get() = DocumentType.WorkoutSession }

@Serializable
@SerialName("training_plan")
data class TrainingPlanDocument(
    override val id: String? = null, override val title: String = "",
    override val tags: List<String> = emptyList(), override val pinned: Boolean = false,
    override val archived: Boolean = false, override val color: String? = null,
    @Serializable(with = LenientInstantSerializer::class) override val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class) override val updatedAt: Instant? = null,
    val weeks: Int = 1, val days: List<PlanDay> = emptyList(),
    val overrides: List<WeekOverride> = emptyList(), val progression: ProgressionRule = ProgressionRule(),
    val exerciseRules: List<ExerciseProgression> = emptyList(),
    val deloadEveryWeeks: Int? = null, val deloadPercent: Double = 10.0, val notes: String = "",
) : FtmDocument { override val documentType: DocumentType get() = DocumentType.TrainingPlan }
