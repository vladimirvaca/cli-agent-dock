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
import com.intellij.util.ui.UIUtil
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import java.util.concurrent.TimeUnit
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
    // Monotonic clock so the ready timeout is immune to wall-clock adjustments.
    private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(agent.readyTimeoutMs)

    /** Guards [onExit] so the termination callback and the exit poll can't both fire it. */
    private var exitNotified = false
    /** The exit poll only trusts a running->stopped transition after it first saw "running". */
    private var sawCommandRunning = false
    /** Consecutive not-running polls observed after [sawCommandRunning]; see [checkExit]. */
    private var stoppedPolls = 0

    init {
        Disposer.register(parentDisposable, spinner)

        // Keep Esc inside the terminal (agents use it to interrupt); see the router
        // for the double-Esc / Shift+Esc ways to leave the dock by keyboard.
        TerminalEscapeRouter.install(project, widget, parentDisposable)

        add(widget.component, CARD_TERMINAL)
        add(loader, CARD_LOADING)

        // Primary, deterministic signal: the shell command is exit-wrapped (see
        // AgentTerminalRunner), so when the agent ends the PTY terminates and this fires.
        // The isCommandRunning safety net only starts polling in showTerminal(): while the
        // shell is still booting, its integration scripts register as a running command
        // that then goes idle before the queued agent command spawns — a poll catching
        // that gap read it as the agent exiting and closed a freshly opened tab.
        widget.addTerminationCallback({ notifyExit() }, parentDisposable)

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

    /**
     * Best-effort safety net for a shell where the exit wrap didn't run (e.g. an agent
     * hard-killed by a signal). Harmless no-op when isCommandRunning is unsupported: the
     * "seen running" guard never flips, so it can never close the tab spuriously. A stop
     * only counts after [STOPPED_CONFIRM_POLLS] consecutive misses, so a momentary idle
     * blip (an agent with no ready markers still spawning after the shell prompt, or a
     * flaky isCommandRunning read) doesn't close a live tab.
     */
    private fun checkExit() {
        if (exitNotified) return
        if (widget.isCommandRunning()) {
            sawCommandRunning = true
            stoppedPolls = 0
        } else if (sawCommandRunning && ++stoppedPolls >= STOPPED_CONFIRM_POLLS) {
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
        if (ready || System.nanoTime() - deadlineNanos >= 0) {
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
        // Only now is the agent known to be up (marker seen) or long past the shell-boot
        // phase (timeout), so the exit poll can no longer mistake startup for an exit.
        scheduleExitCheck()
    }

    private fun createLoaderPanel(): JComponent {
        val content = Box.createVerticalBox().apply {
            spinner.alignmentX = CENTER_ALIGNMENT
            add(spinner)
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(JBLabel(CliAgentDockBundle["loader.starting", agent.displayName]).apply {
                alignmentX = CENTER_ALIGNMENT
                horizontalAlignment = SwingConstants.CENTER
                // Secondary text, matching how the IDE styles progress captions.
                foreground = UIUtil.getContextHelpForeground()
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
        // Consecutive not-running polls (after having seen the command run) required to
        // treat the agent as exited; see checkExit.
        private const val STOPPED_CONFIRM_POLLS = 3
        private const val CARD_TERMINAL = "terminal"
        private const val CARD_LOADING = "loading"
    }
}
