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

    /**
     * Art for each agent. `IconLoader` picks up the `_dark` sibling of every path on its
     * own, so only the light variant is named here. Visible to tests, which assert every
     * path actually resolves — a typo would otherwise only surface as a missing icon at
     * runtime.
     */
    internal val resourcePaths: Map<String, String> = mapOf(
        "claude-code" to "/icons/agents/claudeCode.svg",
        "github-copilot" to "/icons/agents/githubCopilot.svg",
        "openai-codex" to "/icons/agents/openaiCodex.svg",
        "opencode" to "/icons/agents/opencode.svg",
    )

    private val icons: Map<String, Icon> =
        resourcePaths.mapValues { (_, path) -> IconLoader.getIcon(path, AgentIcons::class.java) }

    /** Agents without dedicated art fall back to a generic console icon. */
    fun forAgent(agent: Agent): Icon = icons[agent.id] ?: AllIcons.Debugger.Console
}
