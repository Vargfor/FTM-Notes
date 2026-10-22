package app.ftmnotes.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlcTest {

    @Test
    fun localEventsAreStrictlyIncreasingEvenWhenWallClockStalls() {
        var wall = 1_000L
        val clock = HlcClock("a") { wall }
        val first = clock.now()
        val second = clock.now()
        wall = 900 // clock went backwards
        val third = clock.now()
        assertTrue(first < second && second < third)
        assertTrue(first.encode() < second.encode() && second.encode() < third.encode())
    }

    @Test
    fun receivingARemoteTimestampFromTheFutureOrdersLaterEventsAfterIt() {
        val clock = HlcClock("a") { 1_000L }
        val remote = Hlc(5_000L, 7, "b")
        clock.receive(remote)
        assertTrue(clock.now() > remote)
    }

    @Test
    fun encodeAndParseRoundTrip() {
        val hlc = Hlc(1_761_130_000_000L, 42, "pixel-9")
        assertEquals(hlc, Hlc.parse(hlc.encode()))
    }

    @Test
    fun counterOverflowBorrowsAMillisecond() {
        val clock = HlcClock("a") { 10L }
        repeat(Hlc.MAX_COUNTER + 1) { clock.now() }
        val next = clock.now()
        assertEquals(11L, next.millis)
    }
}
