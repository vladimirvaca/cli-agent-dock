package com.github.vladimirvaca.cliagentdock.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attention rules decide when an agent going quiet is worth interrupting the user
 * for. Pure logic, so tested without the platform fixture.
 *
 * Every test drives [AttentionDecider.onCheck] with output lengths the way
 * [AgentTerminalView]'s poll would: a growing length means the agent is still producing.
 */
class AttentionDeciderTest {

    private fun decider() = AttentionDecider(idleChecksRequired = 3)

    @Test
    fun doesNotFireOnTheFirstCheck() {
        // The first check only establishes a baseline — an already-idle terminal the user
        // never looked at must not immediately claim attention.
        assertFalse(decider().onCheck(outputLength = 500, userIsWatching = false))
    }

    @Test
    fun doesNotFireWhileOutputKeepsGrowing() {
        val d = decider()
        d.onCheck(10, userIsWatching = false)
        for (length in listOf(20, 30, 40, 50, 60)) {
            assertFalse("growing output must not fire", d.onCheck(length, userIsWatching = false))
        }
    }

    @Test
    fun firesOnceOutputStopsForLongEnough() {
        val d = decider()
        d.onCheck(10, userIsWatching = false)
        d.onCheck(20, userIsWatching = false) // the agent produced something
        assertFalse(d.onCheck(20, userIsWatching = false)) // quiet 1
        assertFalse(d.onCheck(20, userIsWatching = false)) // quiet 2
        assertTrue(d.onCheck(20, userIsWatching = false)) // quiet 3 -> speak up
    }

    @Test
    fun neverFiresWhileTheUserIsWatching() {
        val d = decider()
        d.onCheck(10, userIsWatching = true)
        d.onCheck(20, userIsWatching = true)
        repeat(10) {
            assertFalse("no notice is needed while the session is on screen", d.onCheck(20, userIsWatching = true))
        }
    }

    @Test
    fun firesOnlyOncePerQuietPeriod() {
        val d = decider()
        d.onCheck(10, userIsWatching = false)
        d.onCheck(20, userIsWatching = false)
        repeat(2) { d.onCheck(20, userIsWatching = false) }
        assertTrue(d.onCheck(20, userIsWatching = false))
        repeat(10) {
            assertFalse("one notification per turn, not one per poll", d.onCheck(20, userIsWatching = false))
        }
    }

    @Test
    fun rearmsAfterTheAgentProducesMoreOutput() {
        val d = decider()
        d.onCheck(10, userIsWatching = false)
        d.onCheck(20, userIsWatching = false)
        repeat(2) { d.onCheck(20, userIsWatching = false) }
        assertTrue(d.onCheck(20, userIsWatching = false))

        // A new turn: output resumes, goes quiet again, and deserves a fresh notice.
        d.onCheck(30, userIsWatching = false)
        assertFalse(d.onCheck(30, userIsWatching = false))
        assertFalse(d.onCheck(30, userIsWatching = false))
        assertTrue(d.onCheck(30, userIsWatching = false))
    }

    @Test
    fun lookingAtTheSessionMarksCurrentOutputSeen() {
        val d = decider()
        d.onCheck(10, userIsWatching = false)
        d.onCheck(20, userIsWatching = false) // output arrived while away
        d.onCheck(20, userIsWatching = true) // then the user looked at it
        // Leaving again must not resurrect a notice for output already seen.
        repeat(10) {
            assertFalse("output seen on screen must not notify later", d.onCheck(20, userIsWatching = false))
        }
    }
}
