package com.github.vladimirvaca.cliagentdock.settings

import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.github.vladimirvaca.cliagentdock.agent.AgentRegistry
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
@State(name = "CliAgentDockSettings", storages = [Storage("cliAgentDock.xml")])
class AgentSettingsState : PersistentStateComponent<AgentSettingsState.State> {

    class State {
        @JvmField
        var preferredAgentId: String = AgentRegistry.DEFAULT_AGENT_ID

        @JvmField
        var doubleEscFocusesEditor: Boolean = true

        @JvmField
        var showChangedFiles: Boolean = true
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

    /**
     * Whether quickly pressing Esc twice in an agent terminal moves focus back to the
     * editor. A single Esc always goes to the agent; disabling this keeps every Esc in
     * the terminal (for agents that bind a fast double Esc themselves).
     */
    var doubleEscFocusesEditor: Boolean
        get() = state.doubleEscFocusesEditor
        set(value) {
            state.doubleEscFocusesEditor = value
        }

    /**
     * Whether the "Files changed" panel appears below a session's terminal. Tracking
     * itself always runs; this only hides the panel, so re-enabling it mid-session
     * brings back everything the session accumulated meanwhile.
     */
    var showChangedFiles: Boolean
        get() = state.showChangedFiles
        set(value) {
            state.showChangedFiles = value
        }

    /** The currently preferred agent, falling back to the default if unknown/disabled. */
    val preferredAgent: Agent
        get() = AgentRegistry.findById(state.preferredAgentId)?.takeIf { it.enabled }
            ?: AgentRegistry.default()

    companion object {
        fun getInstance(): AgentSettingsState = service()
    }
}
