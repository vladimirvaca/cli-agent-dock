package com.github.vladimirvaca.cliagentdock.terminal

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.Alarm
import com.intellij.util.ui.AnimatedIcon
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * Hosts the agent terminal and, while the agent is starting up, shows a loading card
 * instead of the terminal so the user sees a spinner rather than the raw shell
 * launching the CLI.
 *
 * A [CardLayout] keeps the terminal genuinely hidden (not merely covered) during
 * startup, while its session runs eagerly in the background and renders at the correct
 * size. Once an agent [ready marker][Agent.readyMarkers] appears in the output — or a
 * timeout elapses — we flip to the terminal card, revealing the already-drawn agent.
 */
class AgentTerminalView(
    project: Project,
    parentDisposable: Disposable,
    workingDirectory: String,
    private val agent: Agent,
    executable: File?,
    /**
     * Invoked once when the agent process ends (Ctrl+C, EOF, the agent's own quit command,
     * or a crash). The owner uses this to close the terminal tab. Guaranteed to fire at most
     * once and always on the EDT.
     */
    private val onExit: () -> Unit,
) : JBPanel<AgentTerminalView>(CardLayout()) {

    private val cardLayout get() = layout as CardLayout

    private val widget = AgentTerminalRunner.launch(project, parentDisposable, workingDirectory, agent, executable)
    private val spinner: AnimatedIcon = AsyncProcessIcon.createBig("CliAgentDockStartup")
    private val loader = createLoaderPanel()

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    private val deadline = System.currentTimeMillis() + agent.readyTimeoutMs

    /** Guards [onExit] so the termination callback and the exit poll can't both fire it. */
    private var exitNotified = false
    /** The exit poll only trusts a running->stopped transition after it first saw "running". */
    private var sawCommandRunning = false

    init {
        Disposer.register(parentDisposable, spinner)

        add(widget.component, CARD_TERMINAL)
        add(loader, CARD_LOADING)

        // Primary, deterministic signal: the shell command is exit-wrapped (see
        // AgentTerminalRunner), so when the agent ends the PTY terminates and this fires.
        widget.addTerminationCallback({ notifyExit() }, parentDisposable)
        // Best-effort safety net for a shell where the exit wrap didn't run (e.g. an agent
        // hard-killed by a signal). Harmless no-op when isCommandRunning is unsupported: the
        // "seen running" guard never flips, so it can never close the tab spuriously.
        scheduleExitCheck()

        if (agent.readyMarkers.isEmpty()) {
            // Readiness cannot be detected for this agent; show the terminal directly.
            showTerminal()
        } else {
            cardLayout.show(this, CARD_LOADING)
            spinner.resume()
            scheduleReadyCheck()
        }
    }

    private fun scheduleExitCheck() {
        alarm.addRequest(::checkExit, EXIT_POLL_INTERVAL_MS)
    }

    private fun checkExit() {
        if (exitNotified) return
        if (widget.isCommandRunning()) {
            sawCommandRunning = true
        } else if (sawCommandRunning) {
            // The agent was running and is now gone, but the shell is still alive.
            notifyExit()
            return
        }
        scheduleExitCheck()
    }

    private fun notifyExit() {
        if (exitNotified) return
        exitNotified = true
        // Termination callbacks may arrive off the EDT; hop on so tab manipulation is safe.
        ApplicationManager.getApplication().invokeLater { onExit() }
    }

    private fun scheduleReadyCheck() {
        alarm.addRequest(::checkReady, POLL_INTERVAL_MS)
    }

    private fun checkReady() {
        val output = widget.getText()
        val ready = agent.readyMarkers.any { output.contains(it) }
        if (ready || System.currentTimeMillis() >= deadline) {
            showTerminal()
        } else {
            scheduleReadyCheck()
        }
    }

    private fun showTerminal() {
        if (spinner.isRunning) {
            spinner.suspend()
        }
        Disposer.dispose(spinner)
        cardLayout.show(this, CARD_TERMINAL)
        widget.requestFocus()
    }

    private fun createLoaderPanel(): JComponent {
        val content = Box.createVerticalBox().apply {
            spinner.alignmentX = CENTER_ALIGNMENT
            add(spinner)
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(JBLabel(CliAgentDockBundle["loader.starting", agent.displayName]).apply {
                alignmentX = CENTER_ALIGNMENT
                horizontalAlignment = SwingConstants.CENTER
            })
        }

        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = true
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            add(content, GridBagConstraints())
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 150
        // Slower cadence for the always-on exit watch (the deterministic close comes from
        // addTerminationCallback; this poll is only a best-effort net), so it stays cheap.
        private const val EXIT_POLL_INTERVAL_MS = 500
        private const val CARD_TERMINAL = "terminal"
        private const val CARD_LOADING = "loading"
    }
}
