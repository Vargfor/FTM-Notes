package io.github.vargfor.ftmnotes.core.model

import io.github.vargfor.ftmnotes.core.model.NoteColor.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteColorTest {

    @Test
    fun acceptsCanonicalKeepAndUiNamesInAnyCase() {
        assertEquals(Parsed.Color(NoteColor.Blue), NoteColor.parse("blue"))
        assertEquals(Parsed.Color(NoteColor.Cerulean), NoteColor.parse("CERULEAN")) // Keep Takeout
        assertEquals(Parsed.Color(NoteColor.Gray), NoteColor.parse("Grey"))
        assertEquals(Parsed.Color(NoteColor.Red), NoteColor.parse("coral")) // Keep's current UI name
    }

    @Test
    fun defaultLikeValuesMeanNoColour() {
        listOf("DEFAULT", "default", "none", "", "white").forEach { assertEquals(Parsed.Default, NoteColor.parse(it), it) }
    }

    @Test
    fun hexMapsToTheNearestPaletteColour() {
        assertEquals(Parsed.Color(NoteColor.Yellow), NoteColor.parse("#FFEB3B"))
        assertEquals(Parsed.Color(NoteColor.Green), NoteColor.parse("#8BC34A"))
    }

    @Test
    fun unknownValuesAreReported() {
        assertEquals(Parsed.Unknown, NoteColor.parse("sparkly"))
    }
}
