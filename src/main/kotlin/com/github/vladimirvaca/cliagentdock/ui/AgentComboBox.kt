package com.github.vladimirvaca.cliagentdock.ui

import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.github.vladimirvaca.cliagentdock.agent.AgentRegistry
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer

/**
 * The agent picker shared by the tool-window toolbar and the settings page:
 * the enabled agents from [AgentRegistry], rendered as icon + display name.
 */
fun agentComboBox(): ComboBox<Agent> =
    ComboBox(CollectionComboBoxModel(AgentRegistry.enabledAgents)).apply {
        renderer = SimpleListCellRenderer.create { label, value, _ ->
            value ?: return@create
            label.text = value.displayName
            label.icon = AgentIcons.forAgent(value)
        }
    }
