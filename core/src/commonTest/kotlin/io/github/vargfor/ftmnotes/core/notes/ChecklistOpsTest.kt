package io.github.vargfor.ftmnotes.core.notes

import io.github.vargfor.ftmnotes.core.model.ChecklistItem
import io.github.vargfor.ftmnotes.core.notes.ChecklistOps.Focus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChecklistOpsTest {

    /** Compact fixture: "a", ">b" (child), "a✓" (checked); ids are the texts. */
    private fun list(vararg specs: String) = specs.map { spec ->
        val checked = spec.endsWith("✓")
        val indent = if (spec.startsWith(">")) 1 else 0
        val text = spec.removePrefix(">").removeSuffix("✓")
        ChecklistItem(id = text, text = text, checked = checked, indent = indent)
    }

    private fun List<ChecklistItem>.render() = map { (if (it.indent > 0) ">" else "") + it.text + (if (it.checked) "✓" else "") }

    @Test
    fun enterSplitsAtTheCursorAndKeepsTheIndent() {
        val edit = ChecklistOps.splitLines(list("a", ">milk", "c"), "milk", "mi\nlk", cursor = 3)
        assertEquals(listOf("a", ">mi", ">lk", "c"), edit.items.render())
        val newItem = edit.items[2]
        assertEquals(Focus(newItem.id!!, 0), edit.focus)
        assertTrue(newItem.id != "milk", "new items get fresh ids")
    }

    @Test
    fun pastingSeveralLinesMakesSeveralItemsWithTheCaretAfterThePaste() {
        // "ab|cd" with "x\ny\nz" pasted at the caret.
        val edit = ChecklistOps.splitLines(list("abcd"), "abcd", "abx\ny\nzcd", cursor = 7)
        assertEquals(listOf("abx", "y", "zcd"), edit.items.render())
        assertEquals(Focus(edit.items[2].id!!, 1), edit.focus)
    }

    @Test
    fun enterAtTheEndMakesAnEmptyItem() {
        val edit = ChecklistOps.splitLines(list("a"), "a", "a\n", cursor = 2)
        assertEquals(listOf("a", ""), edit.items.render())
        assertEquals(Focus(edit.items[1].id!!, 0), edit.focus)
    }

    @Test
    fun deletingMovesTheCaretToTheEndOfTheItemAbove() {
        val edit = ChecklistOps.delete(list("one", "", "three"), "")
        assertEquals(listOf("one", "three"), edit.items.render())
        assertEquals(Focus("one", 3), edit.focus)
        assertEquals(Focus("three", 0), ChecklistOps.delete(list("one", "three"), "one").focus)
        assertEquals(null, ChecklistOps.delete(list("only"), "only").focus)
    }

    @Test
    fun backspaceAtTheStartMergesIntoTheItemAbove() {
        val edit = ChecklistOps.mergeWithPrevious(list("milk ", "eggs"), "eggs")
        assertEquals(listOf("milk eggs"), edit.items.render())
        assertEquals(Focus("milk ", 5), edit.focus)
    }

    @Test
    fun indentIsOneLevelAndTheFirstItemCantBeAChild() {
        assertEquals(listOf("a", ">b"), ChecklistOps.setIndent(list("a", "b"), "b", 3).render())
        assertEquals(listOf("a", "b"), ChecklistOps.setIndent(list("a", "b"), "a", 1).render())
    }

    @Test
    fun checkingAParentChecksItsChildrenAndUncheckingAChildUnchecksItsParent() {
        val checked = ChecklistOps.setChecked(list("p", ">c1", ">c2", "q"), "p", true)
        assertEquals(listOf("p✓", ">c1✓", ">c2✓", "q"), checked.render())
        assertEquals(listOf("p", ">c1", ">c2✓", "q"), ChecklistOps.setChecked(checked, "c1", false).render())
    }

    @Test
    fun uncheckAllAndDeleteChecked() {
        assertEquals(listOf("a", "b"), ChecklistOps.uncheckAll(list("a✓", "b")).render())
        assertEquals(listOf("b"), ChecklistOps.deleteChecked(list("a✓", "b", "c✓")).render())
        // An unchecked child whose parent is deleted is promoted rather than lost.
        assertEquals(listOf("x", "b"), ChecklistOps.deleteChecked(list("a✓", ">x", "b")).render())
    }

    @Test
    fun draggingAParentMovesItsChildrenAndNeverSplitsAnotherBlock() {
        val items = list("a", ">a1", "b", ">b1", ">b2", "c")
        // a dragged down over b lands after b's whole block.
        assertEquals(listOf("b", ">b1", ">b2", "a", ">a1", "c"), ChecklistOps.move(items, "a", "b").render())
        // c dragged up over b2 lands before b's block.
        assertEquals(listOf("a", ">a1", "c", "b", ">b1", ">b2"), ChecklistOps.move(items, "c", "b2").render())
    }

    @Test
    fun draggingAChildMovesOnlyThatChild() {
        val items = list("a", ">a1", "b", ">b1")
        assertEquals(listOf("a", "b", ">a1", ">b1"), ChecklistOps.move(items, "a1", "b").render())
        assertEquals(listOf("a", ">a1", ">b1", "b"), ChecklistOps.move(items, "b1", "b").render())
        // A child dragged to the very top becomes a top-level item.
        assertEquals(listOf("a1", "a", "b", ">b1"), ChecklistOps.move(items, "a1", "a").render())
    }

    @Test
    fun moveByStepsOverWholeBlocks() {
        val items = list("a", ">a1", "b", "c")
        assertEquals(listOf("b", "a", ">a1", "c"), ChecklistOps.moveBy(items, "a", +1).render())
        assertEquals(listOf("a", ">a1", "c", "b"), ChecklistOps.moveBy(items, "c", -1).render())
        assertEquals(items, ChecklistOps.moveBy(items, "c", +1))
    }

    @Test
    fun textAndChecklistConvertBothWaysWithoutLosingState() {
        val items = list("Milk", ">Oat", "Eggs✓")
        val text = ChecklistOps.toText(items)
        assertEquals("- Milk\n    - Oat\n- ~~Eggs~~", text)
        assertEquals(listOf("Milk", ">Oat", "Eggs✓"), ChecklistOps.fromText(text).render())
    }

    @Test
    fun anyTextBecomesItemsLineByLine() {
        val items = ChecklistOps.fromText("# Groceries\n\n**Milk**\n- [x] bread\n1. jam\n<br>\n  ")
        assertEquals(listOf("Groceries", "Milk", "bread✓", "jam"), items.render())
        assertTrue(items.all { it.id != null })
    }

    @Test
    fun withIdsOnlyFillsWhatsMissing() {
        val items = ChecklistOps.withIds(listOf(ChecklistItem(id = "keep", text = "a"), ChecklistItem(text = "b")))
        assertEquals("keep", items[0].id)
        assertNotNull(items[1].id)
    }
}
