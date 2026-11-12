package com.github.vladimirvaca.cliagentdock.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attention rules decide when an agent going quiet is worth interrupting the user
 * for. Pure logic, so tested without the platform fixture.
 *
 * Every test drives [AttentionDecider.onCheck] with output lengths (and, where it
 * matters, hashes) the way [AgentTerminalView]'s poll would: a value that differs from
 * the previous check means the agent's output changed.
 */
class AttentionDeciderTest {

    private fun decider() = AttentionDecider(idleChecksRequired = 3)

    /** A turn in progress: output changing on consecutive checks, ending at length 30. */
    private fun AttentionDecider.work(userIsWatching: Boolean) {
        onCheck(10, userIsWatching)
        onCheck(20, userIsWatching)
        onCheck(30, userIsWatching)
    }

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
        d.work(userIsWatching = false)
        assertFalse(d.onCheck(30, userIsWatching = false)) // quiet 1
        assertFalse(d.onCheck(30, userIsWatching = false)) // quiet 2
        assertTrue(d.onCheck(30, userIsWatching = false)) // quiet 3 -> speak up
    }

    @Test
    fun neverFiresWhileTheUserIsWatching() {
        val d = decider()
        d.work(userIsWatching = true)
        repeat(10) {
            assertFalse("no notice is needed while the session is on screen", d.onCheck(30, userIsWatching = true))
        }
    }

    @Test
    fun firesOnlyOncePerQuietPeriod() {
        val d = decider()
        d.work(userIsWatching = false)
        repeat(2) { d.onCheck(30, userIsWatching = false) }
        assertTrue(d.onCheck(30, userIsWatching = false))
        repeat(10) {
            assertFalse("one notification per turn, not one per poll", d.onCheck(30, userIsWatching = false))
        }
    }

    @Test
    fun rearmsAfterTheAgentProducesMoreOutput() {
        val d = decider()
        d.work(userIsWatching = false)
        repeat(2) { d.onCheck(30, userIsWatching = false) }
        assertTrue(d.onCheck(30, userIsWatching = false))

        // A new turn: output resumes, goes quiet again, and deserves a fresh notice.
        d.onCheck(40, userIsWatching = false)
        d.onCheck(50, userIsWatching = false)
        assertFalse(d.onCheck(50, userIsWatching = false))
        assertFalse(d.onCheck(50, userIsWatching = false))
        assertTrue(d.onCheck(50, userIsWatching = false))
    }

    @Test
    fun lookingAtTheSessionMarksCurrentOutputSeen() {
        val d = decider()
        d.work(userIsWatching = false) // output arrived while away
        d.onCheck(30, userIsWatching = true) // then the user looked at it
        // Leaving again must not resurrect a notice for output already seen.
        repeat(10) {
            assertFalse("output seen on screen must not notify later", d.onCheck(30, userIsWatching = false))
        }
    }

    @Test
    fun aThinkingAgentIsNotAnnouncedAsWaiting() {
        val d = decider()
        d.work(userIsWatching = false)
        // Thinking: a spinner and an elapsed-time counter redraw in place, so the text
        // changes on every check while its length does not.
        for (tick in 1..20) {
            assertFalse(
                "an animating agent is busy, not waiting",
                d.onCheck(30, userIsWatching = false, outputHash = tick),
            )
        }
        // It stops: now the quiet period counts.
        assertFalse(d.onCheck(30, userIsWatching = false, outputHash = 20))
        assertFalse(d.onCheck(30, userIsWatching = false, outputHash = 20))
        assertTrue(d.onCheck(30, userIsWatching = false, outputHash = 20))
    }

    @Test
    fun aSameLengthAnimationAloneCountsAsATurn() {
        val d = decider()
        d.onCheck(30, userIsWatching = false)
        for (tick in 1..5) d.onCheck(30, userIsWatching = false, outputHash = tick)
        assertFalse(d.onCheck(30, userIsWatching = false, outputHash = 5))
        assertFalse(d.onCheck(30, userIsWatching = false, outputHash = 5))
        assertTrue(d.onCheck(30, userIsWatching = false, outputHash = 5))
    }

    @Test
    fun aSingleChangedCheckIsNotATurn() {
        val d = decider()
        d.onCheck(10, userIsWatching = false)
        repeat(3) { d.onCheck(10, userIsWatching = false) }
        // A status line ticks once on an idle, unwatched session.
        d.onCheck(13, userIsWatching = false)
        repeat(10) {
            assertFalse("one stray repaint must not be announced", d.onCheck(13, userIsWatching = false))
        }
    }

    @Test
    fun aSingleChangedCheckAfterANoticeDoesNotRepeatIt() {
        val d = decider()
        d.work(userIsWatching = false)
        repeat(2) { d.onCheck(30, userIsWatching = false) }
        assertTrue(d.onCheck(30, userIsWatching = false))
        d.onCheck(33, userIsWatching = false)
        repeat(10) {
            assertFalse("the same turn must not be announced twice", d.onCheck(33, userIsWatching = false))
        }
    }

    @Test
    fun clickingIntoTheTerminalAndLeavingDoesNotNotify() {
        val d = decider()
        d.work(userIsWatching = true) // the turn finished on screen
        d.onCheck(30, userIsWatching = true)
        // The user clicks into the terminal (the TUI repaints on focus-in), looks for a
        // moment and switches to another app (it repaints again on focus-out).
        d.onCheck(34, userIsWatching = true)
        assertFalse(d.onCheck(37, userIsWatching = false, focusChanged = true))
        repeat(10) {
            assertFalse("a finished turn the user looked at is not news", d.onCheck(37, userIsWatching = false))
        }
    }

    @Test
    fun repaintWhenLeavingAnIdleSeenSessionDoesNotNotify() {
        val d = decider()
        d.work(userIsWatching = true) // the turn finished on screen
        repeat(3) { d.onCheck(30, userIsWatching = true) } // and sat idle while watched
        // The user switches app; the TUI repaints on focus-out, changing the buffer.
        assertFalse(d.onCheck(33, userIsWatching = false, focusChanged = true))
        repeat(10) {
            assertFalse("a focus repaint is not a new turn", d.onCheck(33, userIsWatching = false))
        }
    }

    @Test
    fun repaintAfterANoticeDoesNotRepeatIt() {
        val d = decider()
        d.work(userIsWatching = false)
        repeat(2) { d.onCheck(30, userIsWatching = false) }
        assertTrue(d.onCheck(30, userIsWatching = false))
        // The user comes back to the IDE to dismiss the notice (dock still closed), then
        // leaves again; each focus change makes the TUI repaint, here on consecutive
        // checks.
        assertFalse(d.onCheck(35, userIsWatching = false, focusChanged = true))
        assertFalse(d.onCheck(31, userIsWatching = false, focusChanged = true))
        repeat(10) {
            assertFalse("the same quiet period must not be announced twice", d.onCheck(31, userIsWatching = false))
        }
    }

    @Test
    fun repaintAbsorptionIsLimitedToTheSettleWindow() {
        val d = decider()
        d.onCheck(10, userIsWatching = true)
        repeat(4) { d.onCheck(10, userIsWatching = true) }
        d.onCheck(12, userIsWatching = false, focusChanged = true) // repaint, absorbed
        d.onCheck(12, userIsWatching = false) // window over
        // A background turn starts after the window: it is real output and must arm.
        d.onCheck(40, userIsWatching = false)
        d.onCheck(50, userIsWatching = false)
        assertFalse(d.onCheck(50, userIsWatching = false))
        assertFalse(d.onCheck(50, userIsWatching = false))
        assertTrue(d.onCheck(50, userIsWatching = false))
    }

    @Test
    fun leavingWhileTheAgentIsStillWorkingStillNotifies() {
        val d = decider()
        d.work(userIsWatching = true) // mid-turn while watched
        // The user leaves mid-turn; the last output lands together with the focus repaint.
        d.onCheck(40, userIsWatching = false, focusChanged = true)
        assertFalse(d.onCheck(40, userIsWatching = false))
        assertFalse(d.onCheck(40, userIsWatching = false))
        assertTrue("the turn finished unwatched and must be announced", d.onCheck(40, userIsWatching = false))
    }

    @Test
    fun leavingWhileTheAgentIsThinkingWaitsForItToFinish() {
        val d = decider()
        d.work(userIsWatching = true)
        // The user leaves while the agent thinks. The animation inside the settle window
        // is absorbed as a repaint, but it must not be mistaken for quiet.
        assertFalse(d.onCheck(30, userIsWatching = false, focusChanged = true, outputHash = 1))
        for (tick in 2..12) {
            assertFalse("still thinking", d.onCheck(30, userIsWatching = false, outputHash = tick))
        }
        assertFalse(d.onCheck(30, userIsWatching = false, outputHash = 12))
        assertFalse(d.onCheck(30, userIsWatching = false, outputHash = 12))
        assertTrue(d.onCheck(30, userIsWatching = false, outputHash = 12))
    }
}
