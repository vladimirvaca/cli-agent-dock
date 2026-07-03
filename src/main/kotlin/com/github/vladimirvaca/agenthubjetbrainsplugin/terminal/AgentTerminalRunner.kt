package com.github.vladimirvaca.agenthubjetbrainsplugin.terminal

import com.github.vladimirvaca.agenthubjetbrainsplugin.agent.Agent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.io.File

/**
 * Creates an embedded terminal widget and launches the given agent inside it.
 *
 * The user's default shell (chosen by the Terminal plugin) is started in the
 * project directory, then the agent command is queued via
 * [TerminalWidget.sendCommandToExecute], which waits until the shell is ready.
 * We deliberately do not spawn the agent as the shell itself, so the terminal
 * survives the agent exiting and stays cross-platform.
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
        widget.sendCommandToExecute(agent.commandLineFor(executable))
        return widget
    }
}
