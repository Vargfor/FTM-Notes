package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.model.Hlc
import io.github.vargfor.ftmnotes.core.model.HlcClock
import io.github.vargfor.ftmnotes.core.model.Ids
import io.github.vargfor.ftmnotes.data.db.DocumentDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * This device's hybrid logical clock.
 *
 * The node id is random per process: HLC only needs node ids to be unique, not stable, and a
 * fresh id per launch means a database restored onto a new phone can never impersonate the old
 * one. On first use the clock is seeded with the newest HLC in the database, so new edits always
 * sort after everything already stored (even if the wall clock went backwards).
 */
class DeviceClock(
    private val dao: DocumentDao,
    private val wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var clock: HlcClock? = null

    /** Timestamp for a local change. */
    suspend fun now(): Hlc = mutex.withLock { started().now() }

    /** Absorbs a timestamp from elsewhere (restore, sync) so later local edits sort after it. */
    suspend fun receive(remote: Hlc): Hlc = mutex.withLock { started().receive(remote) }

    private suspend fun started(): HlcClock = clock ?: HlcClock(Ids.newId().takeLast(12), wallClock).also { fresh ->
        dao.maxHlc()?.let { runCatching { Hlc.parse(it) }.getOrNull() }?.let(fresh::receive)
        clock = fresh
    }
}
