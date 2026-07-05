package com.github.vladimirvaca.cliagentdock.terminal

import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import java.io.File
import java.util.Locale

/**
 * Creates an embedded terminal widget and launches the given agent inside it.
 *
 * The user's default shell (chosen by the Terminal plugin) is started in the
 * project directory, then the agent command is queued via
 * [TerminalWidget.sendCommandToExecute], which waits until the shell is ready.
 * We launch through the shell (rather than as the shell) so the agent inherits
 * the user's real PATH/env sourced from their profile; the command is wrapped so
 * the shell exits together with the agent (see [exitWrappedCommand]), which lets
 * the caller close the terminal tab once the agent is gone.
 */
object AgentTerminalRunner {

    /**
     * @param parentDisposable disposes the terminal session (dispose it to stop/restart).
     * @param workingDirectory directory the shell starts in (usually the project root).
     * @param executable resolved absolute path of the agent, launched directly so the
     *   shell's PATH is irrelevant; falls back to the bare command name when null.
     */
    fun launch(
        project: Project,
        parentDisposable: Disposable,
        workingDirectory: String,
        agent: Agent,
        executable: File?,
    ): TerminalWidget {
        val runner = LocalTerminalDirectRunner.createTerminalRunner(project)
        val options = ShellStartupOptions.Builder()
            .workingDirectory(workingDirectory)
            .build()

        // deferSessionStartUntilUiShown = false: start the shell eagerly so the agent
        // can boot while it is still hidden behind the loader (see AgentTerminalView).
        val widget = runner.startShellTerminalWidget(parentDisposable, options, false)
        widget.sendCommandToExecute(exitWrappedCommand(project, agent.commandLineFor(executable)))
        return widget
    }

    /**
     * Wraps the agent command so the hosting shell terminates when the agent exits, no
     * matter how it exits (Ctrl+C, EOF, the agent's own quit command, or a crash). This is
     * what lets the tool window close the tab afterwards.
     *
     * The syntax is shell-specific, so we classify the configured shell by its path:
     *  - POSIX shells (bash/zsh/sh/fish, incl. Git Bash): `exec <cmd>` replaces the shell
     *    with the agent, so the PTY dies with the agent while keeping the already-sourced
     *    profile env. It is also the most robust for Ctrl+C — there is no shell left to
     *    return to.
     *  - PowerShell: `& <cmd>; exit`.
     *  - cmd.exe: `<cmd> & exit`.
     *
     * If the shell can't be identified we fall back to the bare command (previous behavior:
     * the shell survives), so an unknown shell degrades gracefully instead of misbehaving.
     */
    private fun exitWrappedCommand(project: Project, command: String): String =
        when (shellFamily(project)) {
            ShellFamily.POSIX -> "exec $command"
            ShellFamily.POWERSHELL -> "& $command; exit"
            ShellFamily.CMD -> "$command & exit"
            ShellFamily.UNKNOWN -> command
        }

    private enum class ShellFamily { POSIX, POWERSHELL, CMD, UNKNOWN }

    private fun shellFamily(project: Project): ShellFamily {
        val shellPath = TerminalProjectOptionsProvider.getInstance(project).shellPath
        // Compare on the lowercased executable name so full paths and .exe suffixes match.
        val name = shellPath.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        return when {
            name.startsWith("pwsh") || name.startsWith("powershell") -> ShellFamily.POWERSHELL
            name.startsWith("cmd") -> ShellFamily.CMD
            name.contains("bash") || name.contains("zsh") || name.contains("fish") ||
                name == "sh" || name.startsWith("sh.") || name.contains("dash") -> ShellFamily.POSIX
            else -> ShellFamily.UNKNOWN
        }
    }
}
