package com.github.vladimirvaca.cliagentdock.actions

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsConfigurable
import com.github.vladimirvaca.cliagentdock.toolWindow.AgentDockService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware

/**
 * The dock's actions, registered in `plugin.xml` rather than built inline by the panel.
 *
 * Registering them buys three things the previous anonymous toolbar actions could not
 * have: they appear in Find Action and Search Everywhere, the user can bind their own
 * shortcuts in Settings > Keymap, and the toolbar can be assembled from an action group
 * declared in XML so each action has exactly one definition.
 *
 * They are deliberately thin: the panel they operate on is reached through
 * [AgentDockService], because the platform constructs registered actions with no
 * arguments and the tool window may not even be open yet.
 */
class NewSessionAction : AnAction(
    CliAgentDockBundle["action.newSession.text"],
    CliAgentDockBundle["action.newSession.description"],
    AllIcons.General.Add,
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AgentDockService.getInstance(project).withPanel { it.openPreferredSession() }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class RestartSessionAction : AnAction(
    CliAgentDockBundle["action.restart.text"],
    CliAgentDockBundle["action.restart.description"],
    AllIcons.Actions.Restart,
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AgentDockService.getInstance(project).attachedPanel?.restartActiveSession()
    }

    /**
     * Disabled with no session to restart — including when the dock has never been
     * opened, where there is no panel to ask. Unlike the other actions this must not
     * open the tool window as a side effect of being invoked, so it reads
     * [AgentDockService.attachedPanel] directly instead of going through `withPanel`.
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled =
            project != null && AgentDockService.getInstance(project).attachedPanel?.hasActiveSession() == true
    }

    // Touches Swing state (the selected tab), so it must be computed on the EDT.
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class OpenDockSettingsAction : AnAction(
    CliAgentDockBundle["action.settings.text"],
    CliAgentDockBundle["action.settings.description"],
    AllIcons.General.Settings,
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(e.project, AgentSettingsConfigurable::class.java)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Shows the dock and puts the caret in the agent terminal, for a mouse-free round trip. */
class FocusDockAction : AnAction(
    CliAgentDockBundle["action.focusDock.text"],
    CliAgentDockBundle["action.focusDock.description"],
    null,
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AgentDockService.getInstance(project).activateDock()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
