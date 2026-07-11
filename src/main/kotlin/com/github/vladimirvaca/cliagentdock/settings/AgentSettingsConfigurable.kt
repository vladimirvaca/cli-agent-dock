package com.github.vladimirvaca.cliagentdock.settings

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.ui.agentComboBox
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel

/**
 * Settings page under Settings > Tools > CLI Agent Dock for choosing the preferred
 * agent. Writes to the same application-level [AgentSettingsState] used by the
 * tool window, so the choice is remembered globally. The UI DSL binding supplies
 * the isModified/apply/reset lifecycle.
 */
class AgentSettingsConfigurable : BoundConfigurable(CliAgentDockBundle["settings.displayName"]) {

    override fun createPanel(): DialogPanel {
        val settings = AgentSettingsState.getInstance()
        return panel {
            row(CliAgentDockBundle["settings.preferredAgent"]) {
                cell(agentComboBox()).bindItem(
                    { settings.preferredAgent },
                    { agent -> agent?.let { settings.preferredAgentId = it.id } },
                )
            }
        }
    }
}
