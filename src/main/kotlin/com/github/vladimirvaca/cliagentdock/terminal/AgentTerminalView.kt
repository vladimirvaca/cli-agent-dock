package com.github.vladimirvaca.cliagentdock.terminal

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.Alarm
import com.intellij.util.ui.AnimatedIcon
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.jediterm.terminal.model.TerminalModelListener
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    /** The configured shell, read off the EDT by the owner; see [AgentTerminalRunner.resolveShellPath]. */
    shellPath: String?,
    /**
     * Invoked once when the agent process ends (Ctrl+C, EOF, the agent's own quit command,
     * or a crash). The owner uses this to close the terminal tab. Guaranteed to fire at most
     * once and always on the EDT.
     */
    private val onExit: () -> Unit,
    /**
     * Watches for the agent going quiet while the user is elsewhere. Driven from this
     * view's existing poll rather than its own timer; null disables attention entirely.
     */
    private val attention: AgentAttentionWatcher? = null,
) : JBPanel<AgentTerminalView>(CardLayout()) {

    private val cardLayout get() = layout as CardLayout

    private val widget = AgentTerminalRunner.launch(project, parentDisposable, workingDirectory, agent, executable, shellPath)
    /** Every stdin write (context sends, Esc) goes through here: off the EDT, in order. */
    private val input = AgentInputWriter(widget)
    private val spinner: AnimatedIcon = AsyncProcessIcon.createBig("CliAgentDockStartup")
    private val loader = createLoaderPanel()

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    // Monotonic clock so the ready timeout is immune to wall-clock adjustments.
    private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(agent.readyTimeoutMs)

    /** Guards [onExit] so the termination callback and the exit poll can't both fire it. */
    private var exitNotified = false
    /** Running->stopped bookkeeping for the exit poll; touched only on the EDT. */
    private val exitConfirmation = ExitConfirmation(STOPPED_CONFIRM_POLLS)
    /**
     * True while a background isCommandRunning probe is outstanding, so a slow probe
     * (it walks the process tree) never gets a second one stacked on top of it.
     */
    private var probeInFlight = false

    /**
     * The JediTerm buffer behind the widget, or null for any other kind of widget. Its
     * change events let [outputLength] skip rebuilding the scrollback while nothing was
     * written.
     */
    private val textBuffer = JBTerminalWidget.asJediTermWidget(widget)?.terminalTextBuffer

    /** Set from the terminal's emulator thread whenever the buffer changes. */
    private val outputDirty = AtomicBoolean(true)

    /** Length of the terminal text as of the last time it was read; EDT only. */
    private var lastOutputLength = 0

    /** True once the terminal card is showing, i.e. the agent is up and can accept input. */
    private var revealed = false

    /**
     * Text handed to [sendInput] before the agent was ready. Writing to the PTY during
     * shell boot would be swallowed or land in the wrong process, so it is held until
     * [showTerminal] and flushed there.
     */
    private var pendingInput: StringBuilder? = null

    init {
        Disposer.register(parentDisposable, spinner)

        // Keep Esc inside the terminal (agents use it to interrupt); see the router
        // for the double-Esc / Shift+Esc ways to leave the dock by keyboard.
        TerminalEscapeRouter.install(project, widget, input, parentDisposable)

        add(widget.component, CARD_TERMINAL)
        add(loader, CARD_LOADING)

        // Primary, deterministic signal: the shell command is exit-wrapped (see
        // AgentTerminalRunner), so when the agent ends the PTY terminates and this fires.
        // The isCommandRunning safety net only starts polling in showTerminal(): while the
        // shell is still booting, its integration scripts register as a running command
        // that then goes idle before the queued agent command spawns — a poll catching
        // that gap read it as the agent exiting and closed a freshly opened tab.
        widget.addTerminationCallback({ notifyExit() }, parentDisposable)

        textBuffer?.let { buffer ->
            // Called on the emulator thread for every write, so it only flips a flag.
            val listener = TerminalModelListener { outputDirty.set(true) }
            buffer.addModelListener(listener)
            Disposer.register(parentDisposable) { buffer.removeModelListener(listener) }
        }

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
        alarm.addRequest(::checkExit, SESSION_POLL_INTERVAL_MS)
    }

    /**
     * Best-effort safety net for a shell where the exit wrap didn't run (e.g. an agent
     * hard-killed by a signal). Harmless no-op when isCommandRunning is unsupported: the
     * "seen running" guard never flips, so it can never close the tab spuriously. A stop
     * only counts after [STOPPED_CONFIRM_POLLS] consecutive misses, so a momentary idle
     * blip (an agent with no ready markers still spawning after the shell prompt, or a
     * flaky isCommandRunning read) doesn't close a live tab.
     *
     * This tick runs on the EDT, but the isCommandRunning probe does not: the Terminal
     * plugin answers it by walking the shell's process tree
     * (`TerminalUtil.hasRunningCommands`), and from 2026.2 the platform asserts that runs
     * on a background thread outside any read action (issue #21). The probe goes to a
     * pooled thread and [onProbeResult] applies its answer back on the EDT; at most one
     * probe is in flight at a time.
     *
     * The tick is deliberately slow ([SESSION_POLL_INTERVAL_MS]). On macOS and Linux each
     * probe starts a `ps` process, and this runs for the whole life of every session,
     * while the deterministic close comes from the termination callback anyway.
     */
    private fun checkExit() {
        if (exitNotified) return
        if (!probeInFlight) {
            probeInFlight = true
            ApplicationManager.getApplication().executeOnPooledThread(::probeCommandRunning)
        }
        // Attention rides this timer instead of adding a second one.
        attention?.onCheck(outputLength())
        scheduleExitCheck()
    }

    /**
     * Length of the terminal's text, the attention check's "did the agent write anything"
     * signal. `getText()` rebuilds the whole scrollback as a string, so it is only called
     * when the buffer reported a change since the last read; an idle session, which is
     * most of a session's life, costs a flag read. Widgets without a JediTerm buffer have
     * no change events and are read every time. Runs on the EDT: `getText()` reads the
     * UI-side buffer.
     */
    private fun outputLength(): Int {
        // Clear the flag before reading: a write landing in between is then both in the
        // text read now and flagged for the next check, never lost.
        if (textBuffer == null || outputDirty.getAndSet(false)) {
            lastOutputLength = widget.getText().length
        }
        return lastOutputLength
    }

    /** Pooled thread: probe the shell, then hop back to the EDT with the answer. */
    private fun probeCommandRunning() {
        val running = try {
            widget.isCommandRunning()
        } catch (e: Exception) {
            // A session torn down mid-probe can throw from the connector. Report "no
            // reading" rather than "stopped", so a failed probe can never close a tab.
            thisLogger().debug("isCommandRunning probe failed", e)
            null
        }
        // any(): the result only updates counters (onExit itself hops through a plain
        // invokeLater), and it must not sit behind a modal dialog while probeInFlight
        // holds back every further probe. The alarm shares the view's lifetime, so a
        // disposed alarm means the session is gone and the result is dropped.
        ApplicationManager.getApplication().invokeLater(
            { onProbeResult(running) },
            ModalityState.any(),
        ) { alarm.isDisposed }
    }

    private fun onProbeResult(running: Boolean?) {
        probeInFlight = false
        if (exitNotified || running == null) return
        if (exitConfirmation.onSample(running)) {
            // The agent was running and is now gone, but the shell is still alive.
            notifyExit()
        }
    }

    /**
     * Types [text] into the running agent **without submitting it**, leaving the user to
     * add their instruction and press Enter. Queued until the agent is ready if called
     * during startup, so callers never have to wait for the session themselves.
     */
    fun sendInput(text: String) {
        if (!revealed) {
            (pendingInput ?: StringBuilder().also { pendingInput = it }).append(text)
            return
        }
        writeToAgent(text)
    }

    /**
     * Writes straight to the agent's stdin. `sendCommandToExecute` would append a newline
     * and run the line, which is the opposite of what the context actions want.
     * [AgentInputWriter] does the PTY write off the EDT and keeps writes in order.
     * See AGENTS.md §4.2.
     */
    private fun writeToAgent(text: String) {
        input.send(text)
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
        revealed = true
        pendingInput?.let {
            pendingInput = null
            writeToAgent(it.toString())
        }
        // Only now is the agent known to be up (marker seen) or long past the shell-boot
        // phase (timeout), so the exit poll can no longer mistake startup for an exit.
        scheduleExitCheck()
    }

    /** Moves focus into the terminal, e.g. when the dock is activated by shortcut. */
    fun focusTerminal() {
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
        // The always-on per-session tick: the exit safety net plus the attention check.
        // Slow on purpose, see checkExit. AttentionDecider's thresholds are counted in
        // these ticks, so changing this changes how long "quiet" is.
        private const val SESSION_POLL_INTERVAL_MS = 2_000
        // Consecutive not-running polls (after having seen the command run) required to
        // treat the agent as exited, i.e. about 4 s; see checkExit.
        private const val STOPPED_CONFIRM_POLLS = 2
        private const val CARD_TERMINAL = "terminal"
        private const val CARD_LOADING = "loading"
    }
}

/**
 * The exit poll's running->stopped rule, kept free of platform types so it can be tested
 * directly. A stop only counts after the command was first seen running (so an
 * isCommandRunning that always answers `false` never fires) and after
 * [stoppedSamplesRequired] consecutive not-running samples (so one idle blip is ignored).
 */
internal class ExitConfirmation(private val stoppedSamplesRequired: Int) {

    private var sawRunning = false
    private var stoppedSamples = 0

    /** Feeds one probe result; returns true exactly once, when the exit is confirmed. */
    fun onSample(running: Boolean): Boolean {
        if (running) {
            sawRunning = true
            stoppedSamples = 0
            return false
        }
        return sawRunning && ++stoppedSamples == stoppedSamplesRequired
    }
}
