package io.github.vargfor.ftmnotes.core.notes

/**
 * Snapshot undo/redo. Callers [record] the state *before* each change. Changes with the same
 * coalesce key inside [coalesceMillis] of each other (typing in one field) form a single step;
 * a null key (structural changes like adding or moving an item) always starts a new step.
 */
class UndoStack<T>(private val limit: Int = 200, private val coalesceMillis: Long = 1_000) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private var lastKey: String? = null
    private var lastAt: Long = Long.MIN_VALUE

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun record(before: T, coalesceKey: String?, atMillis: Long) {
        val coalesce = coalesceKey != null && coalesceKey == lastKey && atMillis - lastAt <= coalesceMillis && undo.isNotEmpty()
        if (!coalesce) {
            undo.addLast(before)
            if (undo.size > limit) undo.removeFirst()
        }
        redo.clear()
        lastKey = coalesceKey
        lastAt = atMillis
    }

    /** @return the state to go back to, or null if there's nothing to undo. */
    fun undo(current: T): T? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        breakCoalescing()
        return previous
    }

    fun redo(current: T): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        breakCoalescing()
        return next
    }

    fun clear() {
        undo.clear()
        redo.clear()
        breakCoalescing()
    }

    private fun breakCoalescing() {
        lastKey = null
        lastAt = Long.MIN_VALUE
    }
}
