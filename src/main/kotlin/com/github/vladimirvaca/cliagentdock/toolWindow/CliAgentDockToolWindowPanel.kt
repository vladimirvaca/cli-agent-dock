package com.github.vladimirvaca.cliagentdock.toolWindow

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.github.vladimirvaca.cliagentdock.agent.AgentExecutableResolver
import com.github.vladimirvaca.cliagentdock.agent.AgentRegistry
import com.github.vladimirvaca.cliagentdock.changes.ChangedFilesPanel
import com.github.vladimirvaca.cliagentdock.changes.SessionFileChangeTracker
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsConfigurable
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsState
import com.github.vladimirvaca.cliagentdock.terminal.AgentTerminalView
import com.github.vladimirvaca.cliagentdock.util.IdeInfo
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.InplaceButton
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * The right-anchored CLI Agent Dock tool window content: a small toolbar to pick an
 * agent and open new sessions, hosting a [JBTabbedPane] where each tab is an embedded
 * terminal running a chosen agent CLI. Multiple sessions (of the same or different
 * agents) can run side by side, each in its own closeable tab.
 */
class CliAgentDockToolWindowPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private companion object {
        /** Share of the tab's height kept by the terminal when the changed-files panel shows. */
        private const val TERMINAL_PROPORTION = 0.80f
    }

    private val settings = AgentSettingsState.getInstance()
    private val tabs = JBTabbedPane()
    private val agentCombo = ComboBox(AgentRegistry.enabledAgents.toTypedArray())
    private val sessions = mutableListOf<AgentSession>()

    init {
        agentCombo.renderer = SimpleListCellRenderer.create("") { it.displayName }
        agentCombo.selectedItem = settings.preferredAgent
        // Selecting an agent only records the preference used for the next new session;
        // existing tabs keep running so the dropdown never disrupts live sessions.
        agentCombo.addActionListener {
            (agentCombo.selectedItem as? Agent)?.let { settings.preferredAgentId = it.id }
        }

        val controls = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(6))).apply {
            add(JBLabel(CliAgentDockBundle["label.agent"]))
            add(agentCombo)
            add(JButton(CliAgentDockBundle["action.newSession.text"]).apply {
                toolTipText = CliAgentDockBundle["action.newSession.description"]
                addActionListener { openSession(selectedAgent()) }
            })
            add(JButton(CliAgentDockBundle["action.restart.text"]).apply {
                toolTipText = CliAgentDockBundle["action.restart.description"]
                addActionListener { currentSession()?.restart() }
            })
        }

        val versionLabel = JBLabel(IdeInfo.describe()).apply {
            foreground = UIUtil.getContextHelpForeground()
            toolTipText = CliAgentDockBundle["ide.version.tooltip"]
        }

        val toolbar = BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(4, 6)
            addToLeft(controls)
            addToRight(versionLabel)
        }

        thisLogger().info("CLI Agent Dock running in ${IdeInfo.describe()}")

        val root = BorderLayoutPanel()
        root.addToTop(toolbar)
        root.addToCenter(tabs)
        setContent(root)

        // Start with a single session for the preferred agent.
        openSession(settings.preferredAgent)
    }

    private fun selectedAgent(): Agent = agentCombo.selectedItem as? Agent ?: settings.preferredAgent

    /** The session whose tab is currently selected, or null when no tabs are open. */
    private fun currentSession(): AgentSession? {
        val index = tabs.selectedIndex
        if (index < 0) return null
        return sessions.firstOrNull { tabs.indexOfTabComponent(it.header) == index }
    }

    private fun openSession(agent: Agent) {
        val session = AgentSession(agent, uniqueTitle(agent.displayName))
        sessions.add(session)
        session.open()
    }

    /** Ensures each tab has a distinct label, e.g. "Claude Code", "Claude Code (2)". */
    private fun uniqueTitle(base: String): String {
        val existing = sessions.map { it.title }.toSet()
        if (base !in existing) return base
        var n = 2
        while ("$base ($n)" in existing) n++
        return "$base ($n)"
    }

    private fun createNotFoundPanel(agent: Agent, onRetry: () -> Unit): JComponent {
        val message = JBLabel(CliAgentDockBundle["notFound.message", agent.displayName, agent.command]).apply {
            horizontalAlignment = SwingConstants.CENTER
        }
        val buttons = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(8))).apply {
            add(JButton(CliAgentDockBundle["notFound.retry"]).apply {
                addActionListener { onRetry() }
            })
            add(JButton(CliAgentDockBundle["notFound.openSettings"]).apply {
                addActionListener {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, AgentSettingsConfigurable::class.java)
                }
            })
        }

        val content = Box.createVerticalBox().apply {
            add(message)
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(buttons)
        }

        // Center the content in the available space.
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            add(content, GridBagConstraints())
        }
    }

    /**
     * A single agent tab. The [header] (title + close button) is stable across restarts,
     * so tab lookups use [JBTabbedPane.indexOfTabComponent] rather than caching an index
     * that would drift as other tabs are opened or closed.
     */
    private inner class AgentSession(private val agent: Agent, val title: String) {

        val header: JComponent = createHeader()
        private var disposable: Disposable? = null
        private var closed = false

        /**
         * The terminal view whose agent exit should close this tab. Cleared/replaced right
         * before a view is intentionally torn down (restart) so that view's late termination
         * callback — which may arrive after we've already swapped in a new session — is ignored
         * rather than closing the tab we just restarted.
         */
        private var liveView: AgentTerminalView? = null

        fun open() {
            val content = createContent()
            tabs.addTab(title, content)
            val index = tabs.indexOfComponent(content)
            tabs.setTabComponentAt(index, header)
            tabs.selectedIndex = index
        }

        fun restart() {
            val index = tabs.indexOfTabComponent(header)
            if (index < 0) return
            disposeSession()
            val content = createContent()
            tabs.setComponentAt(index, content)
            tabs.selectedIndex = index
        }

        fun close() {
            if (closed) return
            closed = true
            liveView = null
            val index = tabs.indexOfTabComponent(header)
            disposeSession()
            if (index >= 0) tabs.removeTabAt(index)
            sessions.remove(this)
        }

        private fun createContent(): JComponent {
            val executable = AgentExecutableResolver.resolve(agent)
            if (executable == null) {
                return createNotFoundPanel(agent, onRetry = ::restart)
            }
            val sessionDisposable = Disposer.newDisposable(parentDisposable, "CliAgentDockTerminalSession")
            disposable = sessionDisposable
            val workingDir = project.basePath ?: System.getProperty("user.home")
            lateinit var view: AgentTerminalView
            view = AgentTerminalView(project, sessionDisposable, workingDir, agent, executable) {
                // Only the current view may close the tab; a replaced (restarted) view's exit is stale.
                if (liveView === view) close()
            }
            liveView = view

            // Terminal on top; a "Files changed" panel appears below only once this
            // session's agent modifies something, and vanishes again when cleared.
            // Expanded it lives in the splitter (resizable); minimized it re-docks as a
            // header-high strip pinned under the terminal.
            val splitter = OnePixelSplitter(true, TERMINAL_PROPORTION)
            splitter.firstComponent = view
            val container = BorderLayoutPanel().addToCenter(splitter)

            lateinit var tracker: SessionFileChangeTracker
            val changedFiles = ChangedFilesPanel(project, workingDir) { tracker.clear() }
            var hasChanges = false

            fun placeChangedFiles() {
                val expanded = hasChanges && !changedFiles.isMinimized
                // Swing re-parents on add, so ordering matters: detach from the slot being
                // vacated before (re)attaching to the active one.
                splitter.secondComponent = if (expanded) changedFiles else null
                if (hasChanges && changedFiles.isMinimized) {
                    container.addToBottom(changedFiles)
                } else if (!expanded) {
                    container.remove(changedFiles)
                }
                container.revalidate()
                container.repaint()
            }

            changedFiles.onMinimizedChanged = { placeChangedFiles() }
            tracker = SessionFileChangeTracker(workingDir, sessionDisposable) { changes ->
                hasChanges = changes.isNotEmpty()
                changedFiles.update(changes)
                placeChangedFiles()
            }
            return container
        }

        private fun disposeSession() {
            disposable?.let { Disposer.dispose(it) }
            disposable = null
        }

        private fun createHeader(): JComponent {
            val closeButton = InplaceButton(
                IconButton(
                    CliAgentDockBundle["tab.close.tooltip"],
                    AllIcons.Actions.Close,
                    AllIcons.Actions.CloseHovered,
                ),
            ) { close() }

            return JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                add(JBLabel(title))
                add(closeButton)
            }
        }
    }
}
