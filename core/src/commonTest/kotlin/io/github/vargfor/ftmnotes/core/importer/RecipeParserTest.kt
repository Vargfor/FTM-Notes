package io.github.vargfor.ftmnotes.core.importer

import io.github.vargfor.ftmnotes.core.importer.recipe.*
import io.github.vargfor.ftmnotes.core.food.engine.*
import io.github.vargfor.ftmnotes.core.backup.ZipWriter
import io.github.vargfor.ftmnotes.core.model.*
import kotlin.test.*
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.time.Instant

class RecipeParserTest {
    @Test fun objectArrayAndGraphRecipesAreFound() {
        val recipe = """{"@type":["Thing","Recipe"],"name":"Soup","description":"<p>Warm soup</p>","recipeYield":"4 servings","prepTime":"PT15M","cookTime":"PT1H30M","totalTime":"PT1H45M","recipeIngredient":["1 l water","2-3 carrots"],"recipeInstructions":[{"@type":"HowToSection","name":"Soup","itemListElement":[{"@type":"HowToStep","text":"Simmer for 20 minutes"}]}],"image":[{"url":"/soup.jpg"}],"nutrition":{"calories":"200 kcal"}}"""
        listOf(recipe, "[$recipe]", """{"@graph":[{"@type":"WebPage"},$recipe]}""").forEach { json ->
            val result = RecipeParser.parseHtml("<html><script type=\"application/ld+json\">$json</script></html>", "https://example.test/soup")
            assertTrue(result.issues.none { it.severity.name == "Error" }, result.issues.toString())
            val draft = result.recipes.single()
            assertEquals("Soup", draft.document.title)
            assertEquals(4.0, draft.document.servings.amount)
            assertEquals(15, draft.document.prepMinutes)
            assertEquals(90, draft.document.cookMinutes)
            assertEquals(105, draft.document.totalMinutes)
            assertEquals(2, draft.document.ingredientGroups.single().items.size)
            assertEquals("Soup", draft.document.steps.single().section)
            assertEquals(listOf("/soup.jpg"), draft.imageUrls)
            assertEquals("200 kcal", draft.document.nutrition["calories"])
        }
    }
    @Test fun multipleRecipesAndMalformedBlocksDoNotHideValidData() {
        val html = """<script type="application/ld+json">invalid</script><script type="application/ld+json">[{"@type":"Recipe","name":"One"},{"@type":"Recipe","name":"Two"}]</script>"""
        val result = RecipeParser.parseHtml(html, "https://example.test")
        assertEquals(listOf("One","Two"), result.recipes.map { it.document.title })
        assertTrue(result.issues.isNotEmpty())
        assertTrue(RecipeParser.parseHtml("<h1>No metadata</h1>", "https://example.test").recipes.isEmpty())
    }
    @Test fun durationsYieldsAndGroupedIngredientsAreRead() {
        mapOf("PT20M" to 20, "PT1H30M" to 90, "P1DT1H" to 1500, "PT30S" to 1, "1 hour 30 minutes" to 90).forEach { (text, expected) ->
            assertEquals(expected, RecipeParser.minutes(text), text)
        }
        assertNull(RecipeParser.minutes("whenever"))
        assertEquals(Servings(2.0,"loaves"), RecipeParser.servings("Makes 2 loaves"))
        val groups = RecipeParser.ingredientGroups(listOf("Dough:", "2 cups flour", "Sauce:", "1 tbsp oil"))
        assertEquals(listOf("Dough","Sauce"), groups.map { it.name })
        assertEquals(5400, StepTimers.find("Bake for 1 hour 30 minutes").single().seconds)
    }
    @Test fun paprikaGzipEntriesAndEmbeddedPhotosImportAlongsideBadEntries() {
        val fs = FakeFileSystem()
        val json = """{"name":"Pancakes","ingredients":"1 cup flour\n2 eggs","directions":"Mix.\nCook.","servings":"4 servings","prep_time":"10 minutes","cook_time":"20 minutes","categories":["Breakfast"],"photo_data":"cGhvdG8="}"""
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8(json) }
        fs.sink("/export.paprikarecipes".toPath()).buffer().use { sink ->
            ZipWriter(sink).use {
                it.add("one.paprikarecipe", compressed.readByteArray(), Instant.parse("2026-01-01T00:00:00Z"))
                it.add("bad.paprikarecipe", "broken".encodeToByteArray(), Instant.parse("2026-01-01T00:00:00Z"))
            }
        }
        val result = PaprikaParser.parse(fs, "/export.paprikarecipes".toPath())
        val recipe = result.recipes.single()
        assertEquals("Pancakes", recipe.document.title)
        assertEquals(2, recipe.document.ingredientGroups.single().items.size)
        assertEquals(10, recipe.document.prepMinutes)
        assertEquals("photo", recipe.imageBytes.single().bytes.decodeToString())
        assertTrue(result.issues.isNotEmpty())
    }
}
