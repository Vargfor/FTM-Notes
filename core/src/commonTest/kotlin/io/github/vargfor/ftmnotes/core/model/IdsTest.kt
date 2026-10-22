package io.github.vargfor.ftmnotes.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdsTest {

    @Test
    fun newIdsAreUniqueVersion7Uuids() {
        val ids = List(1_000) { Ids.newId() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it[14] == '7' }, "UUID version nibble should be 7")
    }

    @Test
    fun newIdsSortByCreationTime() {
        val first = Ids.newId()
        val later = Ids.newId()
        assertTrue(first.substring(0, 13) <= later.substring(0, 13))
    }

    @Test
    fun seededIdsAreDeterministicAndDistinct() {
        assertEquals(Ids.seeded("exercise", "bench-press"), Ids.seeded("exercise", "bench-press"))
        assertNotEquals(Ids.seeded("exercise", "bench-press"), Ids.seeded("exercise", "squat"))
        assertEquals('8', Ids.seeded("exercise", "squat")[14])
    }
}
