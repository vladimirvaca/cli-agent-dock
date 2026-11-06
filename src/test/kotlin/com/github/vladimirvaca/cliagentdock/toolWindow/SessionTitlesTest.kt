package com.github.vladimirvaca.cliagentdock.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Clean-up of user-entered tab names. Pure string handling, so no platform fixture. */
class SessionTitlesTest {

    @Test
    fun keepsAnOrdinaryName() {
        assertEquals("Fix login bug", SessionTitles.normalize("Fix login bug"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("Refactor", SessionTitles.normalize("  Refactor \t"))
    }

    @Test
    fun collapsesInternalWhitespaceAndNewlines() {
        // A pasted multi-line string must not break the single-line tab header.
        assertEquals("feature auth flow", SessionTitles.normalize("feature\n  auth\tflow"))
    }

    @Test
    fun blankOrMissingInputMeansKeepTheCurrentName() {
        assertNull(SessionTitles.normalize(null))
        assertNull(SessionTitles.normalize(""))
        assertNull(SessionTitles.normalize("   \n "))
    }

    @Test
    fun capsTheLengthWithoutLeavingATrailingSpace() {
        val long = "a".repeat(SessionTitles.MAX_LENGTH - 1) + " bcdef"
        val result = SessionTitles.normalize(long)!!
        assertEquals("a".repeat(SessionTitles.MAX_LENGTH - 1), result)
    }
}
