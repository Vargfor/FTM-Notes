package app.ftmnotes.core.model

import kotlin.time.Clock

/**
 * Hybrid logical clock timestamp. Encoded as a fixed-width string so plain string comparison
 * gives causal-ish ordering across devices: `<millis:15>-<counter:5>-<node>`.
 */
data class Hlc(val millis: Long, val counter: Int, val node: String) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int = encode().compareTo(other.encode())

    fun encode(): String =
        millis.toString().padStart(15, '0') + "-" + counter.toString().padStart(5, '0') + "-" + node

    override fun toString(): String = encode()

    companion object {
        const val MAX_COUNTER = 99_999

        fun parse(value: String): Hlc {
            val parts = value.split('-', limit = 3)
            require(parts.size == 3) { "Invalid HLC: $value" }
            return Hlc(parts[0].toLong(), parts[1].toInt(), parts[2])
        }
    }
}

/** Issues monotonically increasing [Hlc]s for this device and absorbs timestamps seen from others. */
class HlcClock(
    private val node: String,
    private val wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var last = Hlc(0, 0, node)

    /** Timestamp for a local event (an edit). */
    fun now(): Hlc {
        val wall = wallClock()
        last = if (wall > last.millis) Hlc(wall, 0, node) else last.bump()
        return last
    }

    /** Merge a remote timestamp so the next local event sorts after everything we've seen. */
    fun receive(remote: Hlc): Hlc {
        val wall = wallClock()
        val maxMillis = maxOf(wall, last.millis, remote.millis)
        val counter = when {
            maxMillis == last.millis && maxMillis == remote.millis -> maxOf(last.counter, remote.counter) + 1
            maxMillis == last.millis -> last.counter + 1
            maxMillis == remote.millis -> remote.counter + 1
            else -> 0
        }
        last = normalise(maxMillis, counter)
        return last
    }

    private fun Hlc.bump(): Hlc = normalise(millis, counter + 1)

    // On counter overflow borrow a millisecond rather than wrapping (keeps ordering strict).
    private fun normalise(millis: Long, counter: Int): Hlc =
        if (counter > Hlc.MAX_COUNTER) Hlc(millis + 1, 0, node) else Hlc(millis, counter, node)
}
