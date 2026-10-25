package io.github.vargfor.ftmnotes.core.notes

import io.github.vargfor.ftmnotes.core.format.MarkdownLines
import io.github.vargfor.ftmnotes.core.format.MdLine
import io.github.vargfor.ftmnotes.core.format.stripMarkdown
import io.github.vargfor.ftmnotes.core.model.ChecklistItem
import io.github.vargfor.ftmnotes.core.model.Ids

/**
 * Pure edits on checklist items. The stored order is the user's order: nothing here sorts checked
 * items away (the "Checked" section is display only).
 *
 * Structure: one level of nesting. An item with `indent = 1` belongs to the nearest `indent = 0`
 * item above it; a parent plus its children is a *block* that checks and moves together.
 * Every function expects items with ids (see [withIds]) and returns normalised items.
 */
object ChecklistOps {
    const val MAX_INDENT = 1

    /** Where the cursor should go after an edit: in item [id], at character [cursor]. */
    data class Focus(val id: String, val cursor: Int)

    data class Edit(val items: List<ChecklistItem>, val focus: Focus?)

    fun newItem(text: String = "", indent: Int = 0): ChecklistItem = ChecklistItem(Ids.newId(), text, checked = false, indent = indent)

    /** Gives every item without an id a fresh one (items from imports or AI output). */
    fun withIds(items: List<ChecklistItem>): List<ChecklistItem> =
        if (items.all { it.id != null }) items else items.map { if (it.id == null) it.copy(id = Ids.newId()) else it }

    /** First item can't be a child; indents are clamped to the supported depth. */
    fun normalise(items: List<ChecklistItem>): List<ChecklistItem> = items.mapIndexed { index, item ->
        val indent = if (index == 0) 0 else item.indent.coerceIn(0, MAX_INDENT)
        if (indent == item.indent) item else item.copy(indent = indent)
    }

    fun isBlank(items: List<ChecklistItem>): Boolean = items.all { it.text.isBlank() }

    /**
     * The text of [id] became [text], which contains line breaks (Enter on any keyboard, or a
     * multi-line paste). The first line stays in the item; every other line becomes a new item
     * right after it, with the same indent. [cursor] is the caret position within [text]; the
     * caret ends up in the item where it was, after the inserted text.
     */
    fun splitLines(items: List<ChecklistItem>, id: String, text: String, cursor: Int): Edit {
        val index = indexOf(items, id)
        val item = items[index]
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val created = lines.drop(1).map { newItem(it, item.indent) }
        val result = items.toMutableList().apply {
            this[index] = item.copy(text = lines.first())
            addAll(index + 1, created)
        }
        // Map the caret to (line, column) in the split text.
        val before = text.substring(0, cursor.coerceIn(0, text.length)).replace("\r\n", "\n")
        val line = before.count { it == '\n' }
        val column = before.length - (before.lastIndexOf('\n') + 1)
        val focusId = if (line == 0) id else created[line - 1].id!!
        return Edit(normalise(result), Focus(focusId, column))
    }

    /** Removes [id]; the caret moves to the end of the item above (or the start of the one below). */
    fun delete(items: List<ChecklistItem>, id: String): Edit {
        val index = indexOf(items, id)
        val result = items.toMutableList().apply { removeAt(index) }
        val focus = when {
            index > 0 -> result[index - 1].let { Focus(it.id!!, it.text.length) }
            result.isNotEmpty() -> Focus(result[0].id!!, 0)
            else -> null
        }
        return Edit(normalise(result), focus)
    }

    /** Backspace at the start of [id]: its text joins the item above, caret at the join. */
    fun mergeWithPrevious(items: List<ChecklistItem>, id: String): Edit {
        val index = indexOf(items, id)
        if (index == 0) return Edit(items, Focus(id, 0))
        val previous = items[index - 1]
        val merged = previous.copy(text = previous.text + items[index].text)
        val result = items.toMutableList().apply {
            this[index - 1] = merged
            removeAt(index)
        }
        return Edit(normalise(result), Focus(merged.id!!, previous.text.length))
    }

    fun setIndent(items: List<ChecklistItem>, id: String, indent: Int): List<ChecklistItem> =
        normalise(items.map { if (it.id == id) it.copy(indent = indent) else it })

    /** Checking a parent checks its children; unchecking a child unchecks its parent. */
    fun setChecked(items: List<ChecklistItem>, id: String, checked: Boolean): List<ChecklistItem> {
        val index = indexOf(items, id)
        val block = blockOf(items, index)
        val parent = if (items[index].indent > 0) parentIndexOf(items, index) else null
        return items.mapIndexed { i, item ->
            when {
                i == index -> item.copy(checked = checked)
                i in block && item.indent > items[index].indent -> item.copy(checked = checked)
                !checked && i == parent -> item.copy(checked = false)
                else -> item
            }
        }
    }

    fun uncheckAll(items: List<ChecklistItem>): List<ChecklistItem> = items.map { if (it.checked) it.copy(checked = false) else it }

    fun deleteChecked(items: List<ChecklistItem>): List<ChecklistItem> = normalise(items.filterNot { it.checked })

    /**
     * Drag and drop: moves [fromId] to where [toId] is. A top-level item moves with its children
     * and lands before or after [toId]'s whole block, so it never splits another parent from its
     * children. A child moves on its own.
     */
    fun move(items: List<ChecklistItem>, fromId: String, toId: String): List<ChecklistItem> {
        if (fromId == toId) return items
        val from = indexOf(items, fromId)
        val to = indexOf(items, toId)
        val moving = if (items[from].indent == 0) blockOf(items, from) else from..from
        if (to in moving) return items
        val block = items.slice(moving)
        val rest = items.filterIndexed { i, _ -> i !in moving }
        val target = rest.indexOfFirst { it.id == toId }
        val movingDown = to > from
        val insertAt = if (items[from].indent == 0) {
            val root = rootIndexOf(rest, target)
            if (movingDown) blockOf(rest, root).last + 1 else root
        } else {
            if (movingDown) target + 1 else target
        }
        return normalise(rest.toMutableList().apply { addAll(insertAt, block) })
    }

    /** One step up or down (accessibility actions on the drag handle). */
    fun moveBy(items: List<ChecklistItem>, id: String, delta: Int): List<ChecklistItem> {
        val index = indexOf(items, id)
        val neighbour = if (items[index].indent == 0) {
            if (delta < 0) items.getOrNull(index - 1)?.let { rootIndexOf(items, index - 1) } else blockOf(items, index).last + 1
        } else {
            index + delta
        }
        val target = neighbour?.let(items::getOrNull) ?: return items
        return move(items, id, target.id!!)
    }

    // ---------------------------------------------------------------- text ⇄ checklist

    private val nestedListItem = Regex("""^(\s{2,}|\t)\s*([-*+]|\d+[.)])\s+""")
    private val listMarker = Regex("""^([-*+]|\d+[.)])\s+""")
    private val taskMarker = Regex("""^\[([ xX])]\s+""")
    private val struck = Regex("""^~~(.+)~~$""")

    /** Each line becomes an item; list nesting becomes indent and `~~struck~~` lines come back checked. */
    fun fromText(markdown: String): List<ChecklistItem> =
        normalise(
            markdown.lines().mapNotNull { raw ->
                if (raw.isBlank() || raw.trim() == "<br>") return@mapNotNull null
                val nested = nestedListItem.containsMatchIn(raw)
                var line = raw.trim().replace(listMarker, "")
                var checked = false
                taskMarker.find(line)?.let { match ->
                    checked = match.groupValues[1] != " "
                    line = line.substring(match.range.last + 1)
                }
                if (struck.matches(line)) checked = true
                val text = stripMarkdown(line).trim()
                if (text.isEmpty()) null else newItem(text, if (nested) 1 else 0).copy(checked = checked)
            }
        )

    /** A bullet list in our Markdown dialect: children nested, checked items struck through. */
    fun toText(items: List<ChecklistItem>): String =
        MarkdownLines.render(
            items.filter { it.text.isNotBlank() }.map { item ->
                MdLine(MdLine.Kind.ListItem(ordered = false, depth = item.indent), if (item.checked) "~~${item.text.trim()}~~" else item.text.trim())
            }
        )

    // ---------------------------------------------------------------- structure

    private fun indexOf(items: List<ChecklistItem>, id: String): Int =
        items.indexOfFirst { it.id == id }.also { require(it >= 0) { "No checklist item $id" } }

    /** [index] plus the children that follow it. */
    fun blockOf(items: List<ChecklistItem>, index: Int): IntRange {
        var end = index
        while (end + 1 < items.size && items[end + 1].indent > items[index].indent) end++
        return index..end
    }

    private fun parentIndexOf(items: List<ChecklistItem>, index: Int): Int? =
        (index - 1 downTo 0).firstOrNull { items[it].indent < items[index].indent }

    private fun rootIndexOf(items: List<ChecklistItem>, index: Int): Int =
        if (items[index].indent == 0) index else parentIndexOf(items, index) ?: index
}
