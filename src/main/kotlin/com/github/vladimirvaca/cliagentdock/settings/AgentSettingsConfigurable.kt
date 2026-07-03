package com.github.vladimirvaca.cliagentdock.settings

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.github.vladimirvaca.cliagentdock.agent.AgentRegistry
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page under Settings > Tools > CLI Agent Dock for choosing the preferred
 * agent. Writes to the same application-level [AgentSettingsState] used by the
 * tool window, so the choice is remembered globally.
 */
class AgentSettingsConfigurable : Configurable {

    private val settings = AgentSettingsState.getInstance()
    private var combo: ComboBox<Agent>? = null

    override fun getDisplayName(): String = CliAgentDockBundle["settings.displayName"]

    override fun createComponent(): JComponent {
        val box = ComboBox(AgentRegistry.enabledAgents.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { it.displayName }
            selectedItem = settings.preferredAgent
        }
        combo = box

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(CliAgentDockBundle["settings.preferredAgent"], box, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val selected = combo?.selectedItem as? Agent ?: return false
        return selected.id != settings.preferredAgentId
    }

    override fun apply() {
        (combo?.selectedItem as? Agent)?.let { settings.preferredAgentId = it.id }
    }

    override fun reset() {
        combo?.selectedItem = settings.preferredAgent
    }

    override fun disposeUIResources() {
        combo = null
    }
}
