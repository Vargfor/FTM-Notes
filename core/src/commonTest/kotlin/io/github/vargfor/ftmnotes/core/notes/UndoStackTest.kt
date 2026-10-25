package io.github.vargfor.ftmnotes.core.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UndoStackTest {

    @Test
    fun undoAndRedoWalkThroughStates() {
        val stack = UndoStack<String>()
        stack.record("a", null, 0)
        stack.record("ab", null, 10)
        assertEquals("ab", stack.undo("abc"))
        assertEquals("a", stack.undo("ab"))
        assertNull(stack.undo("a"))
        assertEquals("ab", stack.redo("a"))
        assertEquals("abc", stack.redo("ab"))
        assertNull(stack.redo("abc"))
    }

    @Test
    fun typingInOneFieldWithinTheWindowIsOneStep() {
        val stack = UndoStack<String>(coalesceMillis = 1_000)
        stack.record("", "item:1", 0)
        stack.record("m", "item:1", 300)
        stack.record("mi", "item:1", 900)
        stack.record("mil", "item:1", 2_500) // paused: a new step
        assertEquals("mil", stack.undo("milk"))
        assertEquals("", stack.undo("mil"))
    }

    @Test
    fun structuralChangesAndOtherFieldsAreTheirOwnSteps() {
        val stack = UndoStack<String>()
        stack.record("s0", "item:1", 0)
        stack.record("s1", null, 10)
        stack.record("s2", null, 20)
        stack.record("s3", "item:2", 30)
        assertEquals("s3", stack.undo("s4"))
        assertEquals("s2", stack.undo("s3"))
        assertEquals("s1", stack.undo("s2"))
    }

    @Test
    fun aNewChangeClearsRedoAndUndoBreaksCoalescing() {
        val stack = UndoStack<String>()
        stack.record("a", "k", 0)
        stack.undo("b")
        stack.record("a", "k", 1) // same key right after an undo: still a new step
        assertFalse(stack.canRedo)
        assertEquals("a", stack.undo("c"))
    }

    @Test
    fun theOldestStepsFallOffPastTheLimit() {
        val stack = UndoStack<Int>(limit = 2)
        (1..3).forEach { stack.record(it, null, it.toLong()) }
        assertEquals(3, stack.undo(4))
        assertEquals(2, stack.undo(3))
        assertNull(stack.undo(2))
    }
}
