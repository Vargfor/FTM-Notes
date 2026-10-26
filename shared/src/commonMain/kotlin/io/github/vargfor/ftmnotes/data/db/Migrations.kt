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
