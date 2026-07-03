package com.github.vladimirvaca.agenthubjetbrainsplugin.settings

import com.github.vladimirvaca.agenthubjetbrainsplugin.agent.Agent
import com.github.vladimirvaca.agenthubjetbrainsplugin.agent.AgentRegistry
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level persisted settings. The preferred agent is a person-level
 * preference (not per-project), so this is stored globally and remembered across
 * restarts and projects. Only the stable agent [id][Agent.id] is persisted.
 */
@Service(Service.Level.APP)
@State(name = "AgentHubSettings", storages = [Storage("agentHub.xml")])
class AgentSettingsState : PersistentStateComponent<AgentSettingsState.State> {

    class State {
        @JvmField
        var preferredAgentId: String = AgentRegistry.DEFAULT_AGENT_ID
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var preferredAgentId: String
        get() = state.preferredAgentId
        set(value) {
            state.preferredAgentId = value
        }

    /** The currently preferred agent, falling back to the default if unknown/disabled. */
    val preferredAgent: Agent
        get() = AgentRegistry.findById(state.preferredAgentId)?.takeIf { it.enabled }
            ?: AgentRegistry.default()

    companion object {
        fun getInstance(): AgentSettingsState = service()
    }
}
