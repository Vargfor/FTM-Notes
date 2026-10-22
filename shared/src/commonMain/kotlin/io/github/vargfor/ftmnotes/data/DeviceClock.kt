package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.model.Hlc
import io.github.vargfor.ftmnotes.core.model.HlcClock
import io.github.vargfor.ftmnotes.core.model.Ids
import io.github.vargfor.ftmnotes.data.db.DevicePrefDao
import io.github.vargfor.ftmnotes.data.db.DevicePrefEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** This device's hybrid logical clock, keyed by a node id that is created once and persisted. */
class DeviceClock(private val prefs: DevicePrefDao) {
    private val mutex = Mutex()
    private var clock: HlcClock? = null

    suspend fun now(): Hlc = mutex.withLock { (clock ?: HlcClock(nodeId()).also { clock = it }).now() }

    private suspend fun nodeId(): String =
        prefs.get(NODE_ID_KEY) ?: Ids.newId().takeLast(12).also { prefs.put(DevicePrefEntity(NODE_ID_KEY, it)) }

    private companion object {
        const val NODE_ID_KEY = "hlc_node_id"
    }
}
