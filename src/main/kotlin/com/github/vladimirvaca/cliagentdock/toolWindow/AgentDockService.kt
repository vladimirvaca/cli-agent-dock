package com.github.vladimirvaca.cliagentdock.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Bridges the dock's registered actions to the live tool window.
 *
 * Actions declared in `plugin.xml` are instantiated by the platform with no arguments,
 * so they cannot close over the panel the way the old inner-class toolbar actions did.
 * They go through this project service instead, which holds whichever
 * [CliAgentDockToolWindowPanel] is currently open.
 *
 * The panel is only attached while the tool window exists. Everything that can be invoked
 * from outside the dock (the editor's send-context action, a keymap shortcut) must
 * therefore cope with it being absent — hence [withPanel], which opens the tool window
 * first and runs the body once the panel is up.
 */
@Service(Service.Level.PROJECT)
class AgentDockService(private val project: Project) {

    /**
     * Written on the EDT when a tool window is created or disposed, and read from action
     * `update`/`actionPerformed`, which also run on the EDT — so no synchronization is
     * needed beyond the identity check in [attach]'s disposal hook.
     */
    private var panel: CliAgentDockToolWindowPanel? = null

    /** The live panel, or null while the tool window has never been opened (or was closed). */
    val attachedPanel: CliAgentDockToolWindowPanel? get() = panel

    fun attach(panel: CliAgentDockToolWindowPanel, parentDisposable: Disposable) {
        this.panel = panel
        // Identity-guarded so a late disposal of a replaced panel can't null out a newer one.
        Disposer.register(parentDisposable) {
            if (this.panel === panel) this.panel = null
        }
    }

    /**
     * Runs [action] against the dock's panel, opening and activating the tool window first
     * when it is not up yet. [ToolWindow.activate]'s callback runs after the content has
     * been created, so the panel is attached by the time the body executes.
     */
    fun withPanel(action: (CliAgentDockToolWindowPanel) -> Unit) {
        panel?.let {
            action(it)
            return
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate { panel?.let(action) }
    }

    /** Shows the dock and moves focus into the selected session's terminal. */
    fun activateDock() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate { panel?.focusActiveSession() }
    }

    /** Whether the user is currently looking at the dock: it is visible and the IDE has focus. */
    fun isDockVisible(): Boolean =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.isVisible == true

    companion object {
        /** Must match the `<toolWindow id=...>` in plugin.xml. */
        const val TOOL_WINDOW_ID = "CLI Agent Dock"

        fun getInstance(project: Project): AgentDockService = project.service()
    }
}
