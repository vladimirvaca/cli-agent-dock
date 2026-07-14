package com.github.vladimirvaca.cliagentdock.terminal

import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalPanel
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import javax.swing.KeyStroke

/**
 * Keeps the Esc key inside the agent terminal instead of letting the platform steal it.
 *
 * The platform's [JBTerminalPanel] runs a built-in `TerminalEscapeKeyListener` on every
 * key event: on a plain Esc it consumes the event and moves focus to the editor — and in
 * any tool window other than the IDE's own Terminal it does so unconditionally (the
 * `terminal.escape.moves.focus.to.editor` advanced setting is only honored inside the
 * Terminal tool window). Agent CLIs, however, rely on Esc (e.g. to interrupt a running
 * response), so in this dock the key must reach the PTY.
 *
 * We attach a [JBTerminalPanel.addPreKeyEventHandler], which runs *before* that listener,
 * consume the plain Esc press and send the ESC byte to the agent ourselves — the exact
 * byte JediTerm would emit for VK_ESCAPE. (JediTerm ignores ISO-control chars in the
 * follow-up KEY_TYPED event, so the byte is never sent twice.)
 *
 * Leaving the dock by keyboard still works:
 *  - **Esc Esc** — a quick double press (within [DOUBLE_PRESS_WINDOW_MS]) focuses the
 *    editor. The first press is still delivered to the agent, so "interrupt, then jump
 *    back to the code" is a single gesture. Some agents bind a fast double Esc themselves
 *    (Claude Code's rewind), so this is a setting; pressing the two Escs slower than the
 *    window still reaches such agent features even while it is enabled.
 *  - **Shift+Esc** — the IDE's standard Hide Active Tool Window shortcut is untouched.
 */
class TerminalEscapeRouter private constructor(
    private val project: Project,
    private val widget: TerminalWidget,
    private val jediWidget: JBTerminalWidget?,
) {

    /** Timestamp of the last plain-Esc press routed to the agent; 0 when none is pending. */
    private var lastEscNanos = 0L

    private fun preHandleKeyEvent(e: KeyEvent) {
        if (e.isConsumed || e.id != KeyEvent.KEY_PRESSED) return
        if (e.keyCode != KeyEvent.VK_ESCAPE || e.modifiersEx != 0) return
        e.consume()
        onEsc()
    }

    private fun onEsc() {
        val now = System.nanoTime()
        val doubleTap = AgentSettingsState.getInstance().doubleEscFocusesEditor &&
            lastEscNanos != 0L && now - lastEscNanos <= DOUBLE_PRESS_WINDOW_NANOS
        if (doubleTap) {
            // The first press already went to the agent; swallow this one and leave.
            lastEscNanos = 0L
            ToolWindowManager.getInstance(project).activateEditorComponent()
        } else {
            lastEscNanos = now
            sendEscToAgent()
        }
    }

    private fun sendEscToAgent() {
        val starter = jediWidget?.terminalStarter
        if (starter != null) {
            // Same async user-input path JediTerm's own key handling uses.
            starter.sendBytes(ESC_BYTES, true)
        } else {
            widget.ttyConnectorAccessor.executeWithTtyConnector { it.write(ESC_BYTES) }
        }
    }

    /**
     * Fallback for widgets that are not JediTerm-based (no [JBTerminalPanel] to hook):
     * a component-local shortcut outranks the keymap's focus-editor action in the IDE
     * key dispatcher, so Esc is routed through [onEsc] instead of leaving the dock.
     */
    private class EscapeAction(private val router: TerminalEscapeRouter) : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) = router.onEsc()
    }

    companion object {
        /** How close together two Esc presses must be to count as "leave the dock". */
        private const val DOUBLE_PRESS_WINDOW_MS = 300L
        private val DOUBLE_PRESS_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(DOUBLE_PRESS_WINDOW_MS)
        private val ESC_BYTES = byteArrayOf(0x1B)

        fun install(project: Project, widget: TerminalWidget, parentDisposable: Disposable) {
            val jediWidget = JBTerminalWidget.asJediTermWidget(widget)
            val router = TerminalEscapeRouter(project, widget, jediWidget)
            val panel = jediWidget?.terminalPanel as? JBTerminalPanel
            if (panel != null) {
                panel.addPreKeyEventHandler(router::preHandleKeyEvent)
            } else {
                EscapeAction(router).registerCustomShortcutSet(
                    CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
                    widget.component,
                    parentDisposable,
                )
            }
        }
    }
}
