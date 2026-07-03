package com.github.vladimirvaca.agenthubjetbrainsplugin

import com.github.vladimirvaca.agenthubjetbrainsplugin.agent.AgentRegistry
import com.github.vladimirvaca.agenthubjetbrainsplugin.settings.AgentSettingsState
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AgentHubTest : BasePlatformTestCase() {

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
        assertEquals("claude", default.commandLine)
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
