package com.github.vladimirvaca.cliagentdock.agent

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
        Agent(
            id = "github-copilot",
            displayName = "GitHub Copilot CLI",
            // `copilot`, installed cross-platform via `npm i -g @github/copilot`, Homebrew,
            // WinGet, or the install script. AgentExecutableResolver already probes those
            // locations (npm global bin, Homebrew, ~/.local/bin, WinGet) on Windows/macOS/Linux.
            command = "copilot",
            // Strings from the interactive TUI's header/footer. They must appear once the
            // UI is ready and must NOT occur in the launch command echo — Copilot launches
            // by absolute path (…\GitHub.Copilot_…\copilot.exe), so the spaced "GitHub
            // Copilot" and the slash-command hints are safe. The readyTimeout is the net
            // if a release changes them. See the "readiness" pattern in AGENTS.md §4.3.
            readyMarkers = listOf("GitHub Copilot", "/help", "/login"),
        ),
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
