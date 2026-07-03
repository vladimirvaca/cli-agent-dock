package com.github.vladimirvaca.cliagentdock.agent

/**
 * Describes a coding agent that can be launched inside the embedded terminal.
 *
 * The [command] is expected to be resolvable via the user's `PATH` on every OS
 * (Windows, macOS, Linux), so no absolute paths are stored here. OS-specific
 * concerns are limited to PATH resolution and shell quoting handled elsewhere.
 */
data class Agent(
    /** Stable identifier persisted in settings, e.g. "claude-code". */
    val id: String,
    /** Human-readable name shown in the UI, e.g. "Claude Code". */
    val displayName: String,
    /** Executable name resolved via PATH, e.g. "claude". */
    val command: String,
    /** Extra arguments passed to the executable. */
    val args: List<String> = emptyList(),
    /** Whether the agent is currently selectable. Future agents may ship disabled. */
    val enabled: Boolean = true,
    /**
     * Substrings that, when they appear in the terminal output, indicate the agent's
     * interactive UI is ready. While none has appeared a loader covers the terminal.
     * Empty means readiness cannot be detected, so no loader is shown.
     */
    val readyMarkers: List<String> = emptyList(),
    /** Maximum time to keep the loader before revealing the terminal anyway. */
    val readyTimeoutMs: Long = 15_000,
) {
    /** The bare command line (executable name via PATH + args). */
    val commandLine: String
        get() = commandLineFor(null)

    /**
     * Builds the command line sent to the shell. When [executable] is provided its
     * absolute path is used (quoted if needed), so the agent launches even when the
     * shell's PATH does not include it; otherwise the bare [command] name is used.
     */
    fun commandLineFor(executable: java.io.File?): String {
        val exe = executable?.absolutePath ?: command
        val quoted = if (exe.any { it.isWhitespace() }) "\"$exe\"" else exe
        return (listOf(quoted) + args).joinToString(" ").trim()
    }
}
