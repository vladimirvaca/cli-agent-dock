package com.github.vladimirvaca.agenthubjetbrainsplugin.toolWindow

import com.github.vladimirvaca.agenthubjetbrainsplugin.AgentHubBundle
import com.github.vladimirvaca.agenthubjetbrainsplugin.agent.Agent
import com.github.vladimirvaca.agenthubjetbrainsplugin.agent.AgentExecutableResolver
import com.github.vladimirvaca.agenthubjetbrainsplugin.agent.AgentRegistry
import com.github.vladimirvaca.agenthubjetbrainsplugin.settings.AgentSettingsConfigurable
import com.github.vladimirvaca.agenthubjetbrainsplugin.settings.AgentSettingsState
import com.github.vladimirvaca.agenthubjetbrainsplugin.terminal.AgentTerminalView
import com.github.vladimirvaca.agenthubjetbrainsplugin.util.IdeInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
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
 * The right-anchored Agent Hub tool window content: a small toolbar to pick and
 * restart the agent, hosting an embedded terminal that runs the selected agent CLI.
 */
class AgentHubToolWindowPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private val settings = AgentSettingsState.getInstance()
    private val centerHost = BorderLayoutPanel()
    private val agentCombo = ComboBox(AgentRegistry.enabledAgents.toTypedArray())

    /** Disposable for the current terminal session; disposing it stops the agent. */
    private var sessionDisposable: Disposable? = null

    init {
        agentCombo.renderer = SimpleListCellRenderer.create("") { it.displayName }
        agentCombo.selectedItem = settings.preferredAgent
        agentCombo.addActionListener {
            val selected = agentCombo.selectedItem as? Agent ?: return@addActionListener
            if (selected.id != settings.preferredAgentId) {
                settings.preferredAgentId = selected.id
                relaunch()
            }
        }

        val controls = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(6))).apply {
            add(JBLabel(AgentHubBundle["label.agent"]))
            add(agentCombo)
            add(JButton(AgentHubBundle["action.restart.text"]).apply {
                addActionListener { relaunch() }
            })
        }

        val versionLabel = JBLabel(IdeInfo.describe()).apply {
            foreground = UIUtil.getContextHelpForeground()
            toolTipText = AgentHubBundle["ide.version.tooltip"]
        }

        val toolbar = BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(4, 6)
            addToLeft(controls)
            addToRight(versionLabel)
        }

        thisLogger().info("Agent Hub running in ${IdeInfo.describe()}")

        val root = BorderLayoutPanel()
        root.addToTop(toolbar)
        root.addToCenter(centerHost)
        setContent(root)

        launch()
    }

    private fun launch() {
        val agent = settings.preferredAgent
        agentCombo.selectedItem = agent
        centerHost.removeAll()

        val executable = AgentExecutableResolver.resolve(agent)
        if (executable != null) {
            val disposable = Disposer.newDisposable(parentDisposable, "AgentHubTerminalSession")
            sessionDisposable = disposable
            val workingDir = project.basePath ?: System.getProperty("user.home")
            val view = AgentTerminalView(project, disposable, workingDir, agent, executable)
            centerHost.addToCenter(view)
        } else {
            centerHost.addToCenter(createNotFoundPanel(agent))
        }

        centerHost.revalidate()
        centerHost.repaint()
    }

    private fun relaunch() {
        sessionDisposable?.let { Disposer.dispose(it) }
        sessionDisposable = null
        launch()
    }

    private fun createNotFoundPanel(agent: Agent): JComponent {
        val message = JBLabel(AgentHubBundle["notFound.message", agent.displayName, agent.command]).apply {
            horizontalAlignment = SwingConstants.CENTER
        }
        val buttons = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(8))).apply {
            add(JButton(AgentHubBundle["notFound.retry"]).apply {
                addActionListener { relaunch() }
            })
            add(JButton(AgentHubBundle["notFound.openSettings"]).apply {
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
}
