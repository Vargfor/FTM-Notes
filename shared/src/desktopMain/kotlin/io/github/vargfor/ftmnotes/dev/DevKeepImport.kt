package io.github.vargfor.ftmnotes.dev

import io.github.vargfor.ftmnotes.core.importer.keep.KeepSource
import io.github.vargfor.ftmnotes.core.importer.keep.KeepTakeoutParser
import io.github.vargfor.ftmnotes.core.model.Ids
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.db.ImportBatchDao
import io.github.vargfor.ftmnotes.data.db.ImportBatchEntity
import io.github.vargfor.ftmnotes.di.initKoin
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * Dev-only: loads a Google Keep Takeout export into the desktop dev database, so UI work happens
 * against real notes. Attachments are skipped until the import pipeline (Run C) stores them.
 *
 * `./gradlew :shared:devImportKeep -Pzips=C:/path/takeout-001.zip,C:/path/takeout-002.zip`
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Pass the export: ./gradlew :shared:devImportKeep -Pzips=a.zip,b.zip (zips or unzipped folders)")
        exitProcess(2)
    }
    val koin = initKoin().koin
    runBlocking {
        val sources = args.map { arg ->
            val path = arg.toPath()
            if (FileSystem.SYSTEM.metadata(path).isDirectory) KeepSource(FileSystem.SYSTEM, path, path.name) else KeepSource.zip(FileSystem.SYSTEM, path)
        }
        val result = KeepTakeoutParser().parse(sources)
        val batch = Ids.newId()
        koin.get<ImportBatchDao>().insert(
            ImportBatchEntity(
                id = batch,
                source = "keep-dev",
                createdAt = Clock.System.now().toEpochMilliseconds(),
                imported = result.notes.size,
                skipped = result.trashedSkipped + result.emptySkipped,
                status = "done",
            )
        )
        val repository = koin.get<DocumentRepository>()
        result.notes.forEach { repository.importNew(it.document, it.createdAt, it.updatedAt, batch) }

        result.issues.forEach { println("${it.severity}  ${it.path}: ${it.message}") }
        println(
            "Imported ${result.notes.size} notes (batch $batch). Skipped ${result.trashedSkipped} trashed and " +
                "${result.emptySkipped} empty. ${result.notes.sumOf { it.attachments.size }} attachments not copied yet."
        )
    }
    exitProcess(0)
}
