package io.github.vargfor.ftmnotes.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Schema history:
 * - v1 (Phase 0): documents + device_prefs. Only ever held test data.
 * - v2 (Phase 1a): tags, full-text search, import batches, attachments, list projection, purge flag.
 * - v3 (Phase 1): revision history, with a tested non-destructive v2 migration.
 * - v4 (Phase 3): Training projections and the active-workout singleton; non-destructive v3 migration.
 *
 * From v2 onward, every schema change needs a non-destructive migration and a migration test.
 */
@Database(
    entities = [
        DocumentEntity::class,
        DocTagEntity::class,
        ImportBatchEntity::class,
        AttachmentEntity::class,
        DevicePrefEntity::class,
        DocumentRevisionEntity::class,
        TrainingSessionEntity::class,
        TrainingSetEntity::class,
        ActiveWorkoutEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun devicePrefDao(): DevicePrefDao
    abstract fun revisionDao(): DocumentRevisionDao
    abstract fun trainingDao(): TrainingDao

    companion object {
        const val FILE_NAME = "ftm-notes.db"
    }
}

// Room generates the actual implementations for each platform.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Shared configuration for every platform's builder: same bundled SQLite everywhere. */
fun RoomDatabase.Builder<AppDatabase>.buildFtmDatabase(): AppDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1) // v1 only ever held test data
        .addMigrations(Migration2To3, Migration3To4)
        .addCallback(FtsCallback)
        .build()
