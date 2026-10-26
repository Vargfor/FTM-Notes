package io.github.vargfor.ftmnotes.feature

import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.feature.notes.NoteEditorViewModel
import io.github.vargfor.ftmnotes.feature.food.*
import kotlinx.coroutines.*
import kotlin.test.*

class EditorAndFoodStateTest {
    private fun scenario(block: suspend ScreenTestEnvironment.() -> Unit) = runBlocking {
        val env = ScreenTestEnvironment()
        try { env.ready(); env.block() } finally { env.close() }
    }

    @Test fun openingAndLeavingImportedMarkdownDoesNotRewriteItOrCreateEmptyNotes() = scenario {
        val id = repository.save(NoteDocument(title = "Imported", content = "> Original quote\n\n" + 96.toChar().toString().repeat(3) + "text\ncode\n" + 96.toChar().toString().repeat(3)))
        val before = db.documentDao().get(id)
        val vm = model("note") { NoteEditorViewModel(id, repository, scope) }
        await { vm.isLoaded }
        onMain { vm.flush() }
        val empty = model("empty") { NoteEditorViewModel("empty", repository, scope) }
        await { empty.isLoaded }
        onMain { empty.flush(); models.clear() }
        // Await all saves queued before this barrier, then compare persisted payload and HLC.
        withContext(Dispatchers.Main) { vm.flushNow(); empty.flushNow() }
        assertEquals(before, db.documentDao().get(id))
        assertNull(repository.get("empty"))
    }

    @Test fun noteAutosavesAndFinalFlushOutlivesTheViewModel() = scenario {
        val id = repository.save(NoteDocument(title = "Before", content = "Original"))
        val vm = model("note") { NoteEditorViewModel(id, repository, scope) }
        await { vm.isLoaded }
        onMain { vm.rich.setMarkdown("Autosaved text") }
        await { (repository.get(id)?.document as? NoteDocument)?.content == "Autosaved text" }
        onMain { vm.rich.setMarkdown("Final text"); models.clear() }
        await { (repository.get(id)?.document as? NoteDocument)?.content == "Final text" }
        assertNull(vm.error)
    }

    @Test fun noteAutosavePreservesConcurrentOrganisationAndTrash() = scenario {
        val id = repository.save(NoteDocument(title = "Before", content = "Original", tags = listOf("Old")))
        val vm = model("note") { NoteEditorViewModel(id, repository, scope) }
        await { vm.isLoaded }
        repository.update(id) { it.withOrganisation(tags = listOf("Later"), pinned = true, color = "blue") }
        repository.moveToTrash(id)
        onMain { vm.edit(vm.draft.copy(title = "After"), "title"); vm.flush() }
        await { repository.get(id)?.document?.title == "After" }
        val stored = repository.get(id)!!
        assertEquals(listOf("Later"), stored.document.tags)
        assertTrue(stored.document.pinned)
        assertEquals("blue", stored.document.color)
        assertNotNull(stored.deletedAt)
    }

    @Test fun recipeFinalFlushPreservesConcurrentOrganisation() = scenario {
        val id = repository.save(RecipeDocument(title = "Before"))
        val vm = model("recipe") { RecipeEditorViewModel(id, repository, scope) }
        await { vm.loaded }
        repository.update(id) { it.withOrganisation(tags = listOf("Dinner"), archived = true) }
        onMain { vm.edit(vm.draft.copy(description = "Final recipe"), "description"); models.clear() }
        await { (repository.get(id)?.document as? RecipeDocument)?.description == "Final recipe" }
        assertEquals(listOf("Dinner"), repository.get(id)!!.document.tags)
        assertTrue(repository.get(id)!!.document.archived)
    }

    @Test fun checklistReorderAndUndoRetainConcurrentLabels() = scenario {
        val id = repository.save(NoteDocument(kind = NoteKind.Checklist,
            items = listOf(ChecklistItem("a", "A"), ChecklistItem("b", "B", checked = true))))
        val vm = model("note") { NoteEditorViewModel(id, repository, scope) }
        await { vm.isLoaded }
        onMain { vm.items({ it.reversed() }) }
        await { (repository.get(id)?.document as? NoteDocument)?.items?.first()?.id == "b" }
        repository.update(id) { it.withOrganisation(tags = listOf("Later")) }
        onMain { vm.undo() }
        await { (repository.get(id)?.document as? NoteDocument)?.items?.first()?.id == "a" }
        val doc = repository.get(id)!!.document as NoteDocument
        assertEquals(listOf("Later"), doc.tags)
        assertTrue(doc.items.first { it.id == "b" }.checked)
    }

    @Test fun shoppingClearCheckedUndoAndRedoPreserveOtherEdits() = scenario {
        val id = repository.save(ShoppingListDocument(items = listOf(
            ShoppingItem("a", "Milk", checked = true), ShoppingItem("b", "Bread"))))
        val vm = model("shopping") { ShoppingViewModel(id, repository, scope) }
        onMain { vm.mutate { it.copy(items = it.items.filterNot { item -> item.checked }) } }
        await { vm.canUndo }
        repository.update(id) { doc -> (doc as ShoppingListDocument).copy(title = "Later", items = doc.items.map { if (it.id == "b") it.copy(text = "Fresh bread") else it }) }
        onMain { vm.undo() }
        await { vm.canRedo }
        var doc = repository.get(id)!!.document as ShoppingListDocument
        assertEquals(listOf("a", "b"), doc.items.filterNot { it.deleted }.mapNotNull { it.id }.sorted())
        assertEquals("Fresh bread", doc.items.first { it.id == "b" }.text)
        assertEquals("Later", doc.title)
        onMain { vm.redo() }
        await { !vm.canRedo }
        doc = repository.get(id)!!.document as ShoppingListDocument
        assertEquals(listOf("b"), doc.items.filterNot { it.deleted }.map { it.id })
        assertEquals("Later", doc.title)
    }

    @Test fun cookProgressAndAbsoluteTimerDeadlineSurviveReopeningWithoutEditingRecipe() = scenario {
        val id = repository.save(RecipeDocument(title = "Soup", steps = listOf(RecipeStep("s", "Simmer", 1200))))
        val before = FtmFormat.encodeDocument(repository.get(id)!!.document)
        val vm = model("view") { RecipeViewModel(id, repository, db.devicePrefDao(), scope, platform) }
        await { vm.loaded }
        onMain {
            vm.update { it.copy(servings = 6.0, units = "us", checkedIngredients = setOf("ingredient"), checkedSteps = setOf("s"), currentStep = 1) }
            vm.startTimer("s/0", 1200)
        }
        await { bridge.timers.isNotEmpty() }
        val saved = vm.state
        val deadline = saved.timerEnds["s/0"]!!
        onMain { models.clear() }
        val reopened = model("reopened") { RecipeViewModel(id, repository, db.devicePrefDao(), scope, platform) }
        await { reopened.loaded }
        assertEquals(saved, reopened.state)
        assertEquals(deadline, bridge.timers[id + "/s/0"])
        assertEquals(before, FtmFormat.encodeDocument(repository.get(id)!!.document))
        onMain { reopened.cancelTimer("s/0") }
        await { db.devicePrefDao().get("food.view." + id)?.contains("s/0") == false }
        assertTrue(bridge.timers.isEmpty())
    }

    @Test fun timerSchedulingFailureKeepsDeadlineAndShowsRecoverableError() = scenario {
        val id = repository.save(RecipeDocument(title = "Soup"))
        bridge.timerFailure = IllegalStateException("Notifications unavailable")
        val vm = model("view") { RecipeViewModel(id, repository, db.devicePrefDao(), scope, platform) }
        await { vm.loaded }
        onMain { vm.startTimer("step", 30) }
        await { vm.error != null }
        assertEquals("Notifications unavailable", vm.error)
        assertNotNull(vm.state.timerEnds["step"])
        onMain { vm.cancelTimer("step"); vm.clearError() }
        assertTrue(vm.state.timerEnds.isEmpty())
        assertNull(vm.error)
    }
}
