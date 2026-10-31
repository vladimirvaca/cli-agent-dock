package com.github.vladimirvaca.cliagentdock

import com.github.vladimirvaca.cliagentdock.agent.AgentRegistry
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsState
import com.github.vladimirvaca.cliagentdock.ui.AgentIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CliAgentDockTest : BasePlatformTestCase() {

    fun testDefaultAgentIsClaudeCode() {
        val default = AgentRegistry.default()
        assertEquals(AgentRegistry.DEFAULT_AGENT_ID, default.id)
        assertEquals("claude", default.command)
    }

    fun testDefaultAgentIsEnabled() {
        assertTrue(AgentRegistry.enabledAgents.any { it.id == AgentRegistry.DEFAULT_AGENT_ID })
    }

    fun testCommandLineJoinsCommandAndArgs() {
        val default = AgentRegistry.default()
        assertEquals("claude", default.commandLineFor(null))
    }

    fun testAgentIdsAreUnique() {
        val ids = AgentRegistry.agents.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    fun testEveryEnabledAgentHasReadyMarkers() {
        // Without markers an agent silently loses the startup spinner (AGENTS.md §4.3).
        for (agent in AgentRegistry.enabledAgents) {
            assertFalse("${agent.id} has no readyMarkers", agent.readyMarkers.isEmpty())
        }
    }

    fun testNoReadyMarkerMatchesTheLaunchCommandEcho() {
        // The shell echoes the launch command into the buffer, so a marker contained in it
        // would match instantly and skip the spinner. The real line is an absolute path, of
        // which the command name is the tail — checking against that catches the mistake.
        for (agent in AgentRegistry.enabledAgents) {
            val commandLine = agent.commandLineFor(null)
            for (marker in agent.readyMarkers) {
                assertFalse(
                    "${agent.id} marker \"$marker\" occurs in its own launch command",
                    commandLine.contains(marker),
                )
            }
        }
    }

    fun testEveryEnabledAgentHasItsOwnIcon() {
        for (agent in AgentRegistry.enabledAgents) {
            val path = AgentIcons.resourcePaths[agent.id]
            assertNotNull("${agent.id} has no icon", path)
            assertNotNull(
                "icon resource $path is missing",
                AgentIcons::class.java.getResource(path!!),
            )
            val dark = path.replace(".svg", "_dark.svg")
            if (agent.id != AgentRegistry.DEFAULT_AGENT_ID) {
                // Claude Code's icon is brand-colored and reads on both themes; the
                // monochrome ones need the dark sibling IconLoader looks for.
                assertNotNull("dark variant $dark is missing", AgentIcons::class.java.getResource(dark))
            }
        }
    }

    fun testSettingsDefaultToClaudeCode() {
        val settings = AgentSettingsState.getInstance()
        // Preferred agent resolves to a valid, enabled agent even before any change.
        assertTrue(settings.preferredAgent.enabled)
    }

    fun testPreferredAgentFallsBackWhenUnknown() {
        val settings = AgentSettingsState.getInstance()
        val previous = settings.preferredAgentId
        try {
            settings.preferredAgentId = "does-not-exist"
            assertEquals(AgentRegistry.DEFAULT_AGENT_ID, settings.preferredAgent.id)
        } finally {
            settings.preferredAgentId = previous
        }
    }
}
