package io.github.vargfor.ftmnotes.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsQueryTest {

    @Test
    fun quotesAndPrefixMatchesEveryWord() {
        assertEquals("\"banana\"* \"bread\"*", FtsQuery.build("  banana   bread "))
    }

    @Test
    fun neutralisesQuerySyntax() {
        assertEquals("\"AND\"* \"title:x\"*", FtsQuery.build("AND title:x"))
        assertEquals("\"say\"\"hi\"*", FtsQuery.build("say\"hi"))
        assertEquals("\"5-3\"*", FtsQuery.build("5-3 - *"))
    }

    @Test
    fun keepsUnicodeWords() {
        assertEquals("\"blåbær\"* \"café\"*", FtsQuery.build("blåbær café"))
    }

    @Test
    fun nothingSearchableGivesNull() {
        assertNull(FtsQuery.build(""))
        assertNull(FtsQuery.build("   "))
        assertNull(FtsQuery.build("- * \" 🎉"))
    }
}
