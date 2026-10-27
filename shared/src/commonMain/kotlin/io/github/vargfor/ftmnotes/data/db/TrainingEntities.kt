package io.github.vargfor.ftmnotes.data.db

import androidx.room3.*

@Entity(tableName = "training_sessions", indices = [Index(value = ["started_at"]), Index(value = ["template_id"]), Index(value = ["plan_id"])])
data class TrainingSessionEntity(
    @PrimaryKey @ColumnInfo(name = "doc_id") val docId: String,
    val status: String, @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "plan_id") val planId: String?,
)
@Entity(tableName = "training_sets", primaryKeys = ["session_id", "set_id"], indices = [Index(value = ["exercise_id", "ended_at"])])
data class TrainingSetEntity(
    @ColumnInfo(name = "session_id") val sessionId: String, @ColumnInfo(name = "set_id") val setId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String, val name: String, val tracking: String,
    val kind: String, val completed: Boolean, @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    val reps: Int?, val seconds: Double?, val metres: Double?, @ColumnInfo(name = "ended_at") val endedAt: Long?,
)
@Entity(tableName = "active_workout")
data class ActiveWorkoutEntity(
    @PrimaryKey val slot: Int = 1, @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "rest_end") val restEnd: Long? = null,
    @ColumnInfo(name = "elapsed_end") val elapsedEnd: Long? = null,
    @ColumnInfo(name = "boot_id") val bootId: Long? = null,
    @ColumnInfo(name = "rest_token") val restToken: String? = null,
    @ColumnInfo(name = "alerted_token") val alertedToken: String? = null,
    @ColumnInfo(name = "exercise_id") val exerciseId: String? = null,
)

@Dao interface TrainingDao {
    @Upsert suspend fun putSession(value: TrainingSessionEntity)
    @Query("DELETE FROM training_sessions WHERE doc_id = :id") suspend fun deleteSession(id: String)
    @Query("DELETE FROM training_sets WHERE session_id = :id") suspend fun deleteSets(id: String)
    @Insert suspend fun insertSets(sets: List<TrainingSetEntity>)
    @Query("SELECT training_sets.* FROM training_sets INNER JOIN training_sessions ON training_sets.session_id = training_sessions.doc_id WHERE exercise_id = :exerciseId AND completed = 1 AND training_sessions.status = 'Finished' ORDER BY training_sets.ended_at DESC")
    fun exerciseHistory(exerciseId: String): kotlinx.coroutines.flow.Flow<List<TrainingSetEntity>>
    @Query("SELECT * FROM training_sessions WHERE status = 'Finished' ORDER BY started_at DESC")
    fun sessions(): kotlinx.coroutines.flow.Flow<List<TrainingSessionEntity>>
    @Query("SELECT * FROM active_workout WHERE slot = 1") suspend fun active(): ActiveWorkoutEntity?
    @Query("SELECT * FROM active_workout WHERE slot = 1") fun observeActive(): kotlinx.coroutines.flow.Flow<ActiveWorkoutEntity?>
    @Upsert suspend fun putActive(value: ActiveWorkoutEntity)
    @Query("DELETE FROM active_workout WHERE slot = 1") suspend fun clearActive()
}
