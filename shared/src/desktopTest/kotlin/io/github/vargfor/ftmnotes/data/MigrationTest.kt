package io.github.vargfor.ftmnotes.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.format.FtmFormat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class MigrationTest {
    @Test fun populatedVersionTwoMigratesWithoutLosingContentOrTags() = runTest {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val directory = Files.createTempDirectory("ftm-migration").toFile()
        val file = File(directory, "upgrade.db")
        val schema = Json.parseToJsonElement(File(root, "shared/schemas/io.github.vargfor.ftmnotes.data.db.AppDatabase/2.json").readText()).jsonObject["database"]!!.jsonObject
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            schema["entities"]!!.jsonArray.forEach { raw ->
                val entity = raw.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                connection.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace('$'.toString() + "{TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index -> connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace('$'.toString() + "{TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = 2")
            val payload = FtmFormat.encodeDocument(NoteDocument(id = "old", title = "Migrated", content = "Kept")).replace("'", "''")
            connection.execSQL("""INSERT INTO documents VALUES('old','note','Migrated','Kept','$payload',1,NULL,0,0,1,1,NULL,0,'000000000000001-00000-old',NULL,1,'hash',NULL,NULL,'Kept',0,0,NULL)""")
            connection.execSQL("INSERT INTO doc_tags VALUES('old','Legacy','legacy')")
        }
        val db = Room.databaseBuilder<AppDatabase>(file.absolutePath).buildFtmDatabase()
        try {
            val row = db.documentDao().get("old")!!
            assertEquals("Migrated", row.title)
            assertEquals(payloadContent(row.payloadJson), "Kept")
            assertEquals("Legacy", db.documentDao().observeTagCounts().first().single().tag)
            assertTrue(db.revisionDao().all().isEmpty())
        } finally { db.close(); directory.deleteRecursively() }
    }
    private fun payloadContent(raw: String) = (FtmFormat.decodeDocument(raw) as NoteDocument).content
}
