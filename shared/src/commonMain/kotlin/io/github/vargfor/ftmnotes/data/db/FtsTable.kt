package io.github.vargfor.ftmnotes.data.db

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The full-text index (`documents_fts`, SQLite FTS5) is created by hand instead of through Room's
 * `@Fts5` entity: Room 3.0.3's annotation processor rejects FTS entities when compiling for iOS
 * (it can't recognise the default "no content entity" on Kotlin/Native). Consequences:
 * - the table isn't in Room's exported schema, so migrations that touch it must do so explicitly;
 * - DAO queries on it use `@SkipQueryVerification` and aren't observable Flows.
 *
 * Rows are maintained by `DocumentRepository` in the same transaction as the document.
 * `doc_id` is UNINDEXED: it's only used to join back to `documents`.
 */
internal object FtsTable {
    const val CREATE = """
        CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5(
            doc_id UNINDEXED, title, body, tags,
            tokenize = 'unicode61 remove_diacritics 2',
            prefix = '2 3'
        )
    """

    /** Repopulates the index from the documents table (used if the index ever goes missing). */
    const val REBUILD = """
        INSERT INTO documents_fts(doc_id, title, body, tags)
        SELECT d.id, d.title, d.plain_text,
               IFNULL((SELECT group_concat(t.tag, ' ') FROM doc_tags t WHERE t.doc_id = d.id), '')
        FROM documents d WHERE d.purged = 0
    """

    const val DROP = "DROP TABLE IF EXISTS documents_fts"
}

internal object FtsCallback : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        connection.execSQL(FtsTable.CREATE)
    }

    override suspend fun onDestructiveMigration(connection: SQLiteConnection) {
        connection.execSQL(FtsTable.DROP)
        connection.execSQL(FtsTable.CREATE)
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        val exists = connection.prepare("SELECT count(*) FROM sqlite_master WHERE name = 'documents_fts'").use { stmt ->
            stmt.step() && stmt.getLong(0) > 0
        }
        if (!exists) {
            connection.execSQL(FtsTable.CREATE)
            connection.execSQL(FtsTable.REBUILD)
        }
    }
}
