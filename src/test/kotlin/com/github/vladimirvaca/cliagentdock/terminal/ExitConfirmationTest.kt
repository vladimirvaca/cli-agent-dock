package com.github.vladimirvaca.cliagentdock.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exit poll's running->stopped rule (AGENTS.md §4.4). Pure logic, so tested without
 * the platform fixture; samples are fed the way [AgentTerminalView] applies each
 * background isCommandRunning probe.
 */
class ExitConfirmationTest {

    private fun confirmation() = ExitConfirmation(stoppedSamplesRequired = 3)

    @Test
    fun neverFiresWithoutHavingSeenTheCommandRun() {
        // An isCommandRunning that is unsupported answers false forever; that must never
        // be read as the agent exiting.
        val c = confirmation()
        repeat(20) { assertFalse(c.onSample(running = false)) }
    }

    @Test
    fun firesAfterTheRequiredConsecutiveStops() {
        val c = confirmation()
        assertFalse(c.onSample(running = true))
        assertFalse(c.onSample(running = false))
        assertFalse(c.onSample(running = false))
        assertTrue(c.onSample(running = false))
    }

    @Test
    fun aRunningSampleResetsTheStopCount() {
        val c = confirmation()
        c.onSample(running = true)
        c.onSample(running = false)
        c.onSample(running = false)
        assertFalse("an idle blip followed by running must re-arm", c.onSample(running = true))
        assertFalse(c.onSample(running = false))
        assertFalse(c.onSample(running = false))
        assertTrue(c.onSample(running = false))
    }

    @Test
    fun firesOnlyOnce() {
        val c = confirmation()
        c.onSample(running = true)
        val fired = (1..10).count { c.onSample(running = false) }
        assertEquals(1, fired)
    }
}
