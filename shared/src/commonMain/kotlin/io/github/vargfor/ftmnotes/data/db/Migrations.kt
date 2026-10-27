package io.github.vargfor.ftmnotes.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration2To3 : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS doc_revisions (
            id TEXT NOT NULL PRIMARY KEY, doc_id TEXT NOT NULL, payload_json TEXT NOT NULL,
            hlc TEXT NOT NULL, created_at INTEGER NOT NULL, deleted_at INTEGER, purged INTEGER NOT NULL
        )""")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_doc_revisions_doc_id ON doc_revisions(doc_id)")
    }
}

object Migration3To4 : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS training_sessions (
            doc_id TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, started_at INTEGER, ended_at INTEGER,
            template_id TEXT, plan_id TEXT)""")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_training_sessions_started_at ON training_sessions(started_at)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_training_sessions_template_id ON training_sessions(template_id)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_training_sessions_plan_id ON training_sessions(plan_id)")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS training_sets (
            session_id TEXT NOT NULL, set_id TEXT NOT NULL, exercise_id TEXT NOT NULL, name TEXT NOT NULL,
            tracking TEXT NOT NULL, kind TEXT NOT NULL, completed INTEGER NOT NULL, weight_kg REAL,
            reps INTEGER, seconds REAL, metres REAL, ended_at INTEGER, PRIMARY KEY(session_id, set_id))""")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_training_sets_exercise_id_ended_at ON training_sets(exercise_id, ended_at)")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS active_workout (
            slot INTEGER NOT NULL PRIMARY KEY, session_id TEXT NOT NULL, rest_end INTEGER, elapsed_end INTEGER,
            boot_id INTEGER, rest_token TEXT, alerted_token TEXT, exercise_id TEXT)""")
    }
}
