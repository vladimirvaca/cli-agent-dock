package com.github.vladimirvaca.cliagentdock.agent

/**
 * Central catalog of known agents. Claude Code is the default; every other entry is
 * plain data, which is the whole point — adding an agent is a line here plus an icon,
 * never new plumbing. See AGENTS.md §4.3 for the `readyMarkers` contract.
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
        Agent(
            id = "openai-codex",
            displayName = "OpenAI Codex CLI",
            // `codex`, installed by the standalone installers (chatgpt.com/codex/install.sh
            // and install.ps1), `npm i -g @openai/codex`, or `brew install --cask codex`.
            // AgentExecutableResolver probes each of those bin dirs — notably the Windows
            // installer's %LOCALAPPDATA%\Programs\OpenAI\Codex\bin.
            command = "codex",
            // The TUI's banner line, composer placeholder and footer hint, all painted in
            // the same first frame (verified against the tui startup snapshots in
            // openai/codex). None is contained in the launch command echo, which is an
            // absolute path ending in codex/codex.exe.
            readyMarkers = listOf(">_ OpenAI Codex", "Ask Codex to do anything", "? for shortcuts"),
        ),
        Agent(
            id = "opencode",
            displayName = "OpenCode",
            // `opencode`, installed by https://opencode.ai/install (→ $HOME/bin, falling back
            // to ~/.opencode/bin), npm/bun/pnpm global, Homebrew, Scoop or Chocolatey.
            command = "opencode",
            // The composer placeholder, which differs between majors: OpenCode 2 prints
            // "Ask anything, / for commands, @ for context…" while 1.x prints
            // `Ask anything... "<rotating example>"`. "Ask anything" is the shared prefix,
            // so it covers both; the ellipsis is dropped because the TUI swaps "…" for "..."
            // in mono mode.
            readyMarkers = listOf("Ask anything", "/ for commands"),
        ),
    )

    /** Agents the user can currently select. */
    val enabledAgents: List<Agent>
        get() = agents.filter { it.enabled }

    fun findById(id: String?): Agent? = agents.firstOrNull { it.id == id }

    fun default(): Agent = findById(DEFAULT_AGENT_ID) ?: agents.first()
}
