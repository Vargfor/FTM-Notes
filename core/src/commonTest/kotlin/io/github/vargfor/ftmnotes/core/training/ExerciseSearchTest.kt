package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.format.*
import io.github.vargfor.ftmnotes.core.model.*
import kotlin.test.*

class ExerciseSearchTest {
    private val bench = ExerciseDocument(id = "bench", title = "Bench Press", aliases = listOf("Benkpress"),
        category = "strength", equipment = "barbell", muscleGroups = listOf("chest"))
    private val stretch = ExerciseDocument(id = "stretch", title = "Chest Stretch", category = "stretching", muscleGroups = listOf("chest"))
    private val catalog = listOf(stretch, bench.copy(id = "archived", archived = true), bench)

    @Test fun searchUsesAllWordsAcrossNamesAliasesMusclesAndEquipment() {
        for (query in listOf("BENKPRESS", "barbell chest", " chest   press ", "strength bench"))
            assertEquals(listOf(bench), ExerciseSearch(query).apply(catalog), query)
        assertTrue(ExerciseSearch("bench dumbbell").apply(catalog).isEmpty())
    }

    @Test fun filtersCombineAndClearingThemRestoresTheAlphabeticLibrary() {
        assertEquals(listOf(stretch), ExerciseSearch(category = "stretching", muscle = "chest").apply(catalog))
        assertEquals(listOf(bench), ExerciseSearch(equipment = "barbell").apply(catalog))
        assertTrue(ExerciseSearch(category = "stretching", equipment = "barbell").apply(catalog).isEmpty())
        assertEquals(listOf(bench, stretch), ExerciseSearch().apply(catalog))
    }

    @Test fun categoryRoundTripsAndOlderExercisesRemainReadable() {
        assertEquals(bench, FtmReader.read(FtmFormat.encodeDocument(bench)).documents.single())
        val older = FtmReader.read("""{"type":"exercise","title":"My exercise"}""")
        assertTrue(older.issues.isEmpty())
        assertEquals("", (older.documents.single() as ExerciseDocument).category)
    }
}
