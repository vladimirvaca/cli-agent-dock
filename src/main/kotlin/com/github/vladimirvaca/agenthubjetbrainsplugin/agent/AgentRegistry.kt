package com.github.vladimirvaca.agenthubjetbrainsplugin.agent

/**
 * Central catalog of known agents. Claude Code is the default and only enabled
 * entry for the current milestone; the rest are stubs so adding an agent later is
 * data, not new plumbing.
 */
object AgentRegistry {

    const val DEFAULT_AGENT_ID = "claude-code"

    val agents: List<Agent> = listOf(
        Agent(
            id = DEFAULT_AGENT_ID,
            displayName = "Claude Code",
            command = "claude",
            readyMarkers = listOf("? for shortcuts", "Welcome to Claude Code"),
        ),
        Agent(id = "github-copilot", displayName = "GitHub Copilot CLI", command = "copilot", enabled = false),
        Agent(id = "openai-codex", displayName = "OpenAI Codex CLI", command = "codex", enabled = false),
        Agent(id = "opencode", displayName = "OpenCode", command = "opencode", enabled = false),
    )

    /** Agents the user can currently select. */
    val enabledAgents: List<Agent>
        get() = agents.filter { it.enabled }

    fun findById(id: String?): Agent? = agents.firstOrNull { it.id == id }

    fun default(): Agent = findById(DEFAULT_AGENT_ID) ?: agents.first()

    /**
     * Returns true if the agent's executable can be found via PATH or a well-known
     * install location. See [AgentExecutableResolver].
     */
    fun isAvailable(agent: Agent): Boolean =
        AgentExecutableResolver.resolve(agent) != null
}
