package com.github.vladimirvaca.cliagentdock.toolWindow

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.github.vladimirvaca.cliagentdock.agent.AgentExecutableResolver
import com.github.vladimirvaca.cliagentdock.changes.ChangedFilesPanel
import com.github.vladimirvaca.cliagentdock.changes.SessionFileChangeTracker
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsConfigurable
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsListener
import com.github.vladimirvaca.cliagentdock.settings.AgentSettingsState
import com.github.vladimirvaca.cliagentdock.terminal.AgentAttentionWatcher
import com.github.vladimirvaca.cliagentdock.terminal.AgentTerminalView
import com.github.vladimirvaca.cliagentdock.ui.AgentIcons
import com.github.vladimirvaca.cliagentdock.ui.agentComboBox
import com.github.vladimirvaca.cliagentdock.util.IdeInfo
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.InplaceButton
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
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

        /** Client property on a tab's content component pointing back to its [AgentSession]. */
        private const val SESSION_KEY = "CliAgentDock.session"

        private const val CARD_SESSIONS = "sessions"
        private const val CARD_EMPTY = "empty"

        /** Action group ids declared in plugin.xml. */
        private const val GROUP_TOOLBAR = "CliAgentDock.Toolbar"
        private const val GROUP_SETTINGS = "CliAgentDock.SettingsToolbar"

        /** ActionPlaces for the two toolbars, used for action-system diagnostics. */
        private const val PLACE_TOOLBAR = "CliAgentDockToolbar"
        private const val PLACE_SETTINGS_TOOLBAR = "CliAgentDockSettingsToolbar"
    }

    private val settings = AgentSettingsState.getInstance()
    private val tabs = JBTabbedPane()
    private val agentCombo = agentComboBox()
    private val sessions = mutableListOf<AgentSession>()

    /** Shown instead of [tabs] once the last session is closed. */
    private val emptyState = JBPanelWithEmptyText().apply {
        emptyText.appendLine(CliAgentDockBundle["emptyState.title"])
        emptyText.appendLine(CliAgentDockBundle["emptyState.start"], SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            openSession(selectedAgent())
        }
    }
    private val contentCards = JBPanel<JBPanel<*>>(CardLayout())

    init {
        agentCombo.selectedItem = settings.preferredAgent
        // Selecting an agent only records the preference used for the next new session;
        // existing tabs keep running so the dropdown never disrupts live sessions.
        agentCombo.addActionListener {
            (agentCombo.selectedItem as? Agent)?.let { settings.preferredAgentId = it.id }
        }

        AgentDockService.getInstance(project).attach(this, parentDisposable)

        // Icon actions (native tool-window style) instead of text buttons; the labels
        // move into the tooltips supplied by the actions' text/description. The groups
        // come from plugin.xml so each action has a single definition that is also
        // reachable from Find Action and bindable in the keymap.
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar(PLACE_TOOLBAR, actionGroup(GROUP_TOOLBAR), true)
        actionToolbar.targetComponent = this

        val controls = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(6))).apply {
            add(JBLabel(CliAgentDockBundle["label.agent"]))
            add(agentCombo)
            add(actionToolbar.component.apply { isOpaque = false })
        }

        thisLogger().info("CLI Agent Dock running in ${IdeInfo.describe()}")

        // Settings sits alone on the far right, away from the session controls: it
        // configures the plugin rather than the current session, and the separation
        // keeps it visible without competing with the actions used constantly.
        val settingsToolbar = ActionManager.getInstance()
            .createActionToolbar(PLACE_SETTINGS_TOOLBAR, actionGroup(GROUP_SETTINGS), true)
        settingsToolbar.targetComponent = this

        toolbar = BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(4, 6)
            addToLeft(controls)
            addToRight(settingsToolbar.component.apply { isOpaque = false })
        }
        contentCards.add(tabs, CARD_SESSIONS)
        contentCards.add(emptyState, CARD_EMPTY)
        setContent(contentCards)

        // Start with a single session for the preferred agent.
        openSession(settings.preferredAgent)
    }

    /** Flips between the tabbed sessions and the empty state as tabs come and go. */
    private fun updateContentCard() {
        val card = if (tabs.tabCount == 0) CARD_EMPTY else CARD_SESSIONS
        (contentCards.layout as CardLayout).show(contentCards, card)
    }

    /**
     * Looks up a group declared in plugin.xml. A missing or mistyped id would otherwise
     * surface as a silently empty toolbar, so fail loudly instead.
     */
    private fun actionGroup(id: String): ActionGroup =
        requireNotNull(ActionManager.getInstance().getAction(id) as? ActionGroup) {
            "Action group '$id' is not registered as a group in plugin.xml"
        }

    // ---- API used by the registered actions, via AgentDockService ----

    /** Opens a session for the agent currently chosen in the picker. */
    fun openPreferredSession() = openSession(selectedAgent())

    fun hasActiveSession(): Boolean = currentSession() != null

    fun restartActiveSession() {
        currentSession()?.restart()
    }

    /** Moves focus into the selected session's terminal, if there is one. */
    fun focusActiveSession() {
        currentSession()?.focusTerminal()
    }

    /**
     * Types [text] into the selected session's agent without submitting it, opening a
     * session first when none is running so the editor actions work from a cold dock.
     * The session queues the text when its agent is still starting.
     */
    fun sendToActiveSession(text: String) {
        val session = currentSession() ?: run {
            openPreferredSession()
            currentSession()
        }
        session?.sendInput(text)
    }

    private fun selectedAgent(): Agent = agentCombo.selectedItem as? Agent ?: settings.preferredAgent

    /** The session whose tab is currently selected, or null when no tabs are open. */
    private fun currentSession(): AgentSession? =
        (tabs.selectedComponent as? JComponent)?.getClientProperty(SESSION_KEY) as? AgentSession

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

    /** The IDE's standard empty-state text with link-style actions, not raw buttons. */
    private fun createNotFoundPanel(agent: Agent, onRetry: () -> Unit): JComponent =
        JBPanelWithEmptyText().apply {
            emptyText.appendLine(CliAgentDockBundle["notFound.title", agent.displayName])
            emptyText.appendLine(
                CliAgentDockBundle["notFound.hint", agent.command],
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
                null,
            )
            emptyText.appendLine(CliAgentDockBundle["notFound.retry"], SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                onRetry()
            }
            emptyText.appendLine(
                CliAgentDockBundle["notFound.openSettings"],
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
            ) {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, AgentSettingsConfigurable::class.java)
            }
        }

    /**
     * Shown while the agent executable is being resolved on a pooled thread: the same
     * spinner-on-editor-background look as [AgentTerminalView]'s startup loader, so the
     * two phases read as one continuous "starting up".
     */
    private fun createResolvingPanel(parentDisposable: Disposable): JComponent {
        val spinner = AsyncProcessIcon.createBig("CliAgentDockResolve")
        Disposer.register(parentDisposable, spinner)
        spinner.resume()
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = true
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            add(spinner, GridBagConstraints())
        }
    }

    /**
     * A single agent tab. The [header] (title + close button) is stable across restarts,
     * so tab lookups use [JBTabbedPane.indexOfTabComponent] rather than caching an index
     * that would drift as other tabs are opened or closed. Each content component carries
     * its session as a [SESSION_KEY] client property, so selected-tab → session resolution
     * is a direct lookup instead of a scan.
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
            updateContentCard()
        }

        fun restart() {
            val index = tabs.indexOfTabComponent(header)
            if (index < 0) return
            disposeSession()
            val content = createContent()
            tabs.setComponentAt(index, content)
            tabs.selectedIndex = index
        }

        /**
         * Types [text] into this session's agent without submitting. A no-op while the
         * executable is still being resolved (no view yet) — the only window in which the
         * dock is open but has nothing to type into.
         */
        fun sendInput(text: String) {
            liveView?.sendInput(text)
        }

        fun focusTerminal() {
            liveView?.focusTerminal()
        }

        /** Brings this session's tab to the front, e.g. from its attention notification. */
        fun select() {
            val index = tabs.indexOfTabComponent(header)
            if (index >= 0) tabs.selectedIndex = index
        }

        fun close() {
            if (closed) return
            closed = true
            liveView = null
            val index = tabs.indexOfTabComponent(header)
            disposeSession()
            if (index >= 0) tabs.removeTabAt(index)
            sessions.remove(this)
            updateContentCard()
        }

        private fun createContent(): JComponent =
            buildContent().apply { putClientProperty(SESSION_KEY, this@AgentSession) }

        /**
         * Resolution scans PATH and well-known install dirs — real disk I/O on a cache
         * miss (on Windows it stats every WinGet package dir) — so it runs on a pooled
         * thread instead of freezing the EDT on first open or retry. The returned
         * container shows a spinner meanwhile and swaps in the terminal (or the
         * not-found panel) when the lookup lands; a session restarted or closed while
         * resolving just drops the stale result via the expired condition.
         */
        private fun buildContent(): JComponent {
            val sessionDisposable = Disposer.newDisposable(parentDisposable, "CliAgentDockTerminalSession")
            disposable = sessionDisposable
            // Disposal and the expired check both run on the EDT, so a plain flag suffices.
            var expired = false
            Disposer.register(sessionDisposable) { expired = true }
            val container = BorderLayoutPanel().addToCenter(createResolvingPanel(sessionDisposable))
            ApplicationManager.getApplication().executeOnPooledThread {
                val executable = AgentExecutableResolver.resolve(agent)
                ApplicationManager.getApplication().invokeLater(
                    {
                        container.removeAll()
                        val content = if (executable == null) {
                            createNotFoundPanel(agent, onRetry = ::restart)
                        } else {
                            createTerminalContent(sessionDisposable, executable)
                        }
                        container.addToCenter(content)
                        container.revalidate()
                        container.repaint()
                    },
                    { expired },
                )
            }
            return container
        }

        /** The live terminal plus the session's changed-files tracking, wired together. */
        private fun createTerminalContent(sessionDisposable: Disposable, executable: File): JComponent {
            val workingDir = project.basePath ?: System.getProperty("user.home")
            // "Watching" means the dock is visible *and* this session's tab is the one on
            // screen: a background tab finishing while the user reads another one is
            // exactly the case worth announcing.
            val attention = AgentAttentionWatcher(
                project = project,
                sessionTitle = title,
                isUserWatching = {
                    AgentDockService.getInstance(project).isDockVisible() && currentSession() === this
                },
                onOpenRequested = {
                    AgentDockService.getInstance(project).withPanel {
                        select()
                        focusTerminal()
                    }
                },
            )
            lateinit var view: AgentTerminalView
            view = AgentTerminalView(
                project, sessionDisposable, workingDir, agent, executable,
                onExit = {
                    // Only the current view may close the tab; a replaced (restarted) view's exit is stale.
                    if (liveView === view) close()
                },
                attention = attention,
            )
            liveView = view

            // Terminal on top; a "Files changed" panel appears below only once this
            // session's agent modifies something (and the setting allows it), and
            // vanishes again when cleared. Expanded it lives in the splitter (resizable);
            // minimized it re-docks as a header-high strip pinned under the terminal.
            val splitter = OnePixelSplitter(true, TERMINAL_PROPORTION)
            splitter.firstComponent = view
            val container = BorderLayoutPanel().addToCenter(splitter)

            lateinit var tracker: SessionFileChangeTracker
            val changedFiles = ChangedFilesPanel(project, workingDir) { tracker.clear() }
            var hasChanges = false

            fun placeChangedFiles() {
                val shown = hasChanges && settings.showChangedFiles
                val expanded = shown && !changedFiles.isMinimized
                // Swing re-parents on add, so ordering matters: detach from the slot being
                // vacated before (re)attaching to the active one.
                splitter.secondComponent = if (expanded) changedFiles else null
                if (shown && changedFiles.isMinimized) {
                    container.addToBottom(changedFiles)
                } else if (!expanded) {
                    container.remove(changedFiles)
                }
                container.revalidate()
                container.repaint()
            }

            changedFiles.onMinimizedChanged = { placeChangedFiles() }
            // Tracking runs regardless of the setting — it only gates visibility — so
            // toggling it back on mid-session restores the accumulated list intact.
            ApplicationManager.getApplication().messageBus
                .connect(sessionDisposable)
                .subscribe(AgentSettingsListener.TOPIC, AgentSettingsListener { placeChangedFiles() })
            tracker = SessionFileChangeTracker(project, workingDir, sessionDisposable) { changes ->
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
                add(JBLabel(title, AgentIcons.forAgent(agent), SwingConstants.LEADING))
                add(closeButton)
            }
        }
    }
}
