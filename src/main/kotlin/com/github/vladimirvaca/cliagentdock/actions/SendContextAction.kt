package com.github.vladimirvaca.cliagentdock.actions

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.github.vladimirvaca.cliagentdock.toolWindow.AgentDockService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Hands the agent what the user is currently looking at.
 *
 * Inserts a reference to the current file — and the selected line range, when there is a
 * selection — into the running agent's prompt **without submitting it**, so the user types
 * the actual instruction after it. That non-submitting behaviour is the point: the action
 * supplies the context, the user supplies the intent.
 *
 * It sends a *reference* rather than the code itself because every supported agent can
 * read files on its own; pasting the snippet would duplicate what the agent would fetch
 * anyway and lose the surrounding file. `@path` is understood across Claude Code, Codex,
 * OpenCode and Copilot CLI, so this stays agent-agnostic — no per-agent branches.
 */
class SendContextAction : AnAction(
    CliAgentDockBundle["action.sendContext.text"],
    CliAgentDockBundle["action.sendContext.description"],
    null,
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val reference = buildReference(project, file, e.getData(CommonDataKeys.EDITOR))
        // Opens the dock when it is closed; the session queues the text if its agent is
        // still starting, so invoking this as the first thing in a session still works.
        AgentDockService.getInstance(project).withPanel { it.sendToActiveSession(reference) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun buildReference(project: Project, file: VirtualFile, editor: Editor?): String {
        val base = project.guessProjectDir()
        // Forward slashes even on Windows: that is what getRelativePath yields and what the
        // agents expect. Files outside the project fall back to the absolute path.
        val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
        val selection = editor?.let { selectedLines(it) }
        return AgentContextReference.format(path, selection?.first, selection?.second)
    }

    /**
     * 1-based inclusive line range of the selection, or null when nothing is selected.
     *
     * A selection that ends exactly at the start of a line (the usual result of selecting
     * whole lines downward) would otherwise report one line too many, so that trailing
     * line is dropped.
     */
    private fun selectedLines(editor: Editor): Pair<Int, Int>? {
        val model = editor.selectionModel
        if (!model.hasSelection()) return null
        val document = editor.document
        val start = model.selectionStart
        val end = model.selectionEnd
        val startLine = document.getLineNumber(start)
        var endLine = document.getLineNumber(end)
        if (endLine > startLine && end == document.getLineStartOffset(endLine)) endLine--
        return (startLine + 1) to (endLine + 1)
    }
}

/** Formatting of the agent-facing file reference, separated out so it can be tested directly. */
internal object AgentContextReference {

    /**
     * Builds the text inserted into the agent's prompt: `@path`, optionally suffixed with
     * `:line` or `:start-end`. A trailing space is included so the user can start typing
     * their instruction immediately without first pressing space.
     */
    fun format(path: String, startLine: Int?, endLine: Int?): String {
        val range = when {
            startLine == null -> ""
            endLine == null || endLine == startLine -> ":$startLine"
            else -> ":$startLine-$endLine"
        }
        return "@$path$range "
    }
}
