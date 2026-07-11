package com.github.vladimirvaca.cliagentdock.ui

import com.github.vladimirvaca.cliagentdock.agent.Agent
import com.github.vladimirvaca.cliagentdock.agent.AgentRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 16x16 icons identifying each agent in the combo box and session tab titles.
 * Keyed by [Agent.id] (see [AgentRegistry]) so the [Agent] model stays UI-free.
 */
object AgentIcons {

    private val claudeCode = IconLoader.getIcon("/icons/agents/claudeCode.svg", AgentIcons::class.java)
    private val githubCopilot = IconLoader.getIcon("/icons/agents/githubCopilot.svg", AgentIcons::class.java)

    /** Agents without dedicated art fall back to a generic console icon. */
    fun forAgent(agent: Agent): Icon = when (agent.id) {
        "claude-code" -> claudeCode
        "github-copilot" -> githubCopilot
        else -> AllIcons.Debugger.Console
    }
}
