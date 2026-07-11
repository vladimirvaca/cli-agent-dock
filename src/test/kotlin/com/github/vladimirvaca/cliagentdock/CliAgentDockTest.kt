package com.github.vladimirvaca.cliagentdock

import com.github.vladimirvaca.cliagentdock.agent.AgentRegistry
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsState
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
