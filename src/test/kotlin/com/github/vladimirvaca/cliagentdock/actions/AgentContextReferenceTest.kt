package com.github.vladimirvaca.cliagentdock.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text handed to the agent when the user sends editor context. Pure formatting, so
 * tested without the platform fixture.
 */
class AgentContextReferenceTest {

    @Test
    fun wholeFileIsJustAnAtPath() {
        assertEquals("@src/main/kotlin/Foo.kt ", AgentContextReference.format("src/main/kotlin/Foo.kt", null, null))
    }

    @Test
    fun singleLineSelectionUsesOneNumber() {
        assertEquals("@Foo.kt:12 ", AgentContextReference.format("Foo.kt", 12, 12))
    }

    @Test
    fun collapsedRangeIsTreatedAsASingleLine() {
        // A caret with no real range should not read as "12-12".
        assertEquals("@Foo.kt:12 ", AgentContextReference.format("Foo.kt", 12, null))
    }

    @Test
    fun multiLineSelectionUsesARange() {
        assertEquals("@Foo.kt:120-145 ", AgentContextReference.format("Foo.kt", 120, 145))
    }

    @Test
    fun alwaysEndsWithASpaceSoTheUserCanTypeImmediately() {
        // The reference is inserted without being submitted; the user's instruction follows
        // it on the same line, so the separator has to already be there.
        for (ref in listOf(
            AgentContextReference.format("Foo.kt", null, null),
            AgentContextReference.format("Foo.kt", 1, 1),
            AgentContextReference.format("Foo.kt", 1, 9),
        )) {
            assertTrue("'$ref' must end with a space", ref.endsWith(" "))
            assertTrue("'$ref' must not submit a line", !ref.contains("\n"))
        }
    }
}
