package com.github.vladimirvaca.cliagentdock.changes

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.actions.VcsContextFactory
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SideBorder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.icons.toStrokeIcon
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

/**
 * The "Files changed" strip shown below a session's terminal, listing what the agent
 * touched (see [SessionFileChangeTracker]). Rows use the IDE's VCS colors — green
 * created, blue modified, struck-through gray deleted — and behave like hyperlinks:
 * hovering highlights and underlines a row (hand cursor), a single click opens the VCS
 * diff view for that file so the change is visible without leaving the tool window. A
 * small open-file icon before the file-type icon offers a second, more direct action —
 * clicking it opens the file itself instead of its diff. It appears only on the hovered
 * row (an empty placeholder keeps the geometry stable elsewhere). Deleted rows have
 * nothing to open, so they never show it, and the row itself is only clickable (to show
 * the diff) when Git still knows about the deletion; for files Git doesn't track at all,
 * a row click falls back to opening the file. The header offers a shortcut to the IDE's
 * commit view on the left and, on the right, a red clear button — set apart from the
 * minimize chevron by a wider gap since it's destructive. The owner shows/hides the
 * whole panel, so it renders assuming content.
 */
class ChangedFilesPanel(
    private val project: Project,
    basePath: String,
    onClear: () -> Unit,
) : BorderLayoutPanel() {

    private val basePath = FileUtil.toSystemIndependentName(basePath)
    private val model = CollectionListModel<ChangedFile>()

    // getToolTipText is overridden because JList renderers aren't real interactive
    // children: without this, hovering the open-file icon would show no tooltip at all.
    private val list = object : JBList<ChangedFile>(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = locationToIndex(event.point)
            if (row < 0 || !onOpenIcon(event, row)) return null
            return CliAgentDockBundle["changedFiles.openFile.tooltip"]
        }
    }
    // A SimpleColoredComponent rather than a JBLabel so the hover underline is a text
    // attribute, not HTML markup swapped in and out of the label.
    private val countLabel = SimpleColoredComponent().apply {
        ipad = JBUI.emptyInsets()
        border = JBUI.Borders.empty()
    }
    private var changeCount = 0
    private var countHovered = false
    private val scroll = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
    private val minimizeButton = InplaceButton(
        IconButton(CliAgentDockBundle["changedFiles.minimize.tooltip"], AllIcons.General.ChevronDown),
    ) { toggleMinimized() }

    /**
     * When minimized only the header strip stays visible; the owner re-places the panel
     * (out of the splitter, pinned below the terminal) via [onMinimizedChanged].
     */
    var isMinimized = false
        private set

    /** Notified after [isMinimized] flips, so the owner can re-place the panel. */
    var onMinimizedChanged: () -> Unit = {}

    /** Row index under the mouse, -1 when none; drives the hyperlink hover rendering. */
    private var hoveredIndex = -1

    // The file-type icon + relative path, unchanged from before the open-file icon was
    // added; wrapped by [rowRenderer] below so it sits to the right of that icon.
    private val textRenderer = object : ColoredListCellRenderer<ChangedFile>() {
        override fun customizeCellRenderer(
            list: JList<out ChangedFile>,
            value: ChangedFile,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val name = value.path.substringAfterLast('/')
            icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
            val relative = FileUtil.getRelativePath(basePath, value.path, '/') ?: value.path
            val hovered = index == hoveredIndex && isClickable(value)
            if (hovered && !selected) {
                background = UIUtil.getListSelectionBackground(false)
            }
            append(relative, attributesFor(value.kind, hovered))
        }
    }

    // Shows [AllIcons.Actions.EditSource] only on the hovered row; elsewhere an empty
    // icon of the same size keeps rows from shifting as the mouse moves. [onOpenIcon]
    // hit-tests clicks against this component's laid-out bounds, since JList renderers
    // aren't real interactive children and never receive events of their own.
    private val openIcon = JBLabel(EmptyIcon.ICON_16).apply {
        border = JBUI.Borders.emptyRight(4)
    }

    private val rowRenderer = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = true
        add(openIcon, BorderLayout.WEST)
        add(textRenderer, BorderLayout.CENTER)
    }

    init {
        list.cellRenderer = ListCellRenderer<ChangedFile> { l, value, index, selected, hasFocus ->
            textRenderer.getListCellRendererComponent(l, value, index, selected, hasFocus)
            rowRenderer.background = textRenderer.background
            openIcon.background = textRenderer.background
            openIcon.icon = if (value.kind != ChangeKind.DELETED && index == hoveredIndex) {
                AllIcons.Actions.EditSource
            } else {
                EmptyIcon.ICON_16
            }
            rowRenderer
        }

        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                val row = rowAt(e) ?: return
                val value = model.getElementAt(row)
                if (onOpenIcon(e, row)) openInEditor(value) else open(value)
            }

            override fun mouseMoved(e: MouseEvent) {
                val row = rowAt(e)
                setHovered(row ?: -1)
                val clickable = row != null && (isClickable(model.getElementAt(row)) || onOpenIcon(e, row))
                list.cursor =
                    if (clickable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }

            override fun mouseExited(e: MouseEvent) {
                setHovered(-1)
                list.cursor = Cursor.getDefaultCursor()
            }
        }
        list.addMouseListener(mouse)
        list.addMouseMotionListener(mouse)

        // Count label behaves like a link — same action as the commit button.
        countLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        countLabel.toolTipText = CliAgentDockBundle["changedFiles.openCommit.tooltip"]
        countLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) openCommitView()
            }
            override fun mouseEntered(e: MouseEvent) = setCountHovered(true)
            override fun mouseExited(e: MouseEvent) = setCountHovered(false)
        })

        val commitButton = InplaceButton(
            IconButton(CliAgentDockBundle["changedFiles.openCommit.tooltip"], AllIcons.Actions.Commit),
        ) { openCommitView() }

        val clearButton = InplaceButton(
            IconButton(
                CliAgentDockBundle["changedFiles.clear.tooltip"],
                toStrokeIcon(AllIcons.Actions.GC, CLEAR_ICON_COLOR),
            ),
        ) { onClear() }

        val left = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(countLabel)
            add(commitButton)
        }

        // Clear is destructive, so a wider gap keeps it from being brushed against when
        // aiming for the minimize chevron.
        val right = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(12))).apply {
            isOpaque = false
            add(clearButton)
            add(minimizeButton)
        }

        val header = BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            addToLeft(left)
            addToRight(right)
        }

        addToTop(header)
        addToCenter(scroll)

        // A hairline with a little breathing room above it, rather than a line jammed
        // straight against the terminal content, reads as an intentional section break.
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.emptyTop(6),
            IdeBorderFactory.createBorder(SideBorder.TOP),
        )
    }

    private fun toggleMinimized() {
        isMinimized = !isMinimized
        scroll.isVisible = !isMinimized
        val icon = if (isMinimized) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown
        minimizeButton.setIcons(icon, icon, icon)
        minimizeButton.toolTipText =
            CliAgentDockBundle[if (isMinimized) "changedFiles.restore.tooltip" else "changedFiles.minimize.tooltip"]
        onMinimizedChanged()
    }

    /** Replaces the shown list with the tracker's latest cumulative snapshot. */
    fun update(changes: List<ChangedFile>) {
        hoveredIndex = -1
        model.replaceAll(changes)
        changeCount = changes.size
        renderCountLabel()
    }

    private fun setCountHovered(hovered: Boolean) {
        if (countHovered == hovered) return
        countHovered = hovered
        renderCountLabel()
    }

    /** Re-renders the count text, underlined while hovered so it reads as a link. */
    private fun renderCountLabel() {
        countLabel.clear()
        val style = if (countHovered) SimpleTextAttributes.STYLE_UNDERLINE else SimpleTextAttributes.STYLE_PLAIN
        countLabel.append(CliAgentDockBundle["changedFiles.header"], SimpleTextAttributes(style, null))
        // The counter is secondary information, so it renders grayed like IDE counters do.
        countLabel.append(" " + CliAgentDockBundle["changedFiles.count", changeCount], SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    /** The row whose cell actually contains the event point, or null (list may have dead space). */
    private fun rowAt(e: MouseEvent): Int? {
        val index = list.locationToIndex(e.point)
        if (index < 0 || !list.getCellBounds(index, index).contains(e.point)) return null
        return index
    }

    private fun setHovered(index: Int) {
        if (hoveredIndex == index) return
        val previous = hoveredIndex
        hoveredIndex = index
        // Hover only restyles the row left and the row entered; repainting just those
        // two cells keeps mouse movement from redrawing the whole list.
        repaintRow(previous)
        repaintRow(index)
    }

    private fun repaintRow(index: Int) {
        if (index < 0 || index >= model.size) return
        list.getCellBounds(index, index)?.let { list.repaint(it) }
    }

    /** A deleted row is only actionable once Git recognizes it as a change to diff against. */
    private fun isClickable(value: ChangedFile): Boolean =
        value.kind != ChangeKind.DELETED || changeFor(value) != null

    /**
     * Whether [e] — already known to land somewhere in [row]'s cell — lands specifically
     * on that row's open-file icon rather than its text. [rowRenderer] isn't a real child
     * of [list] (JList paints cell renderers, it doesn't add them to the hierarchy), so
     * there's no component to receive its own click: the renderer is laid out at the
     * cell's actual bounds and probed directly instead.
     */
    private fun onOpenIcon(e: MouseEvent, row: Int): Boolean {
        val value = model.getElementAt(row)
        if (value.kind == ChangeKind.DELETED) return false
        val cellBounds = list.getCellBounds(row, row) ?: return false
        textRenderer.getListCellRendererComponent(list, value, row, false, false)
        openIcon.icon = AllIcons.Actions.EditSource
        rowRenderer.setBounds(0, 0, cellBounds.width, cellBounds.height)
        rowRenderer.doLayout()
        val hit = SwingUtilities.getDeepestComponentAt(rowRenderer, e.x - cellBounds.x, e.y - cellBounds.y)
        return hit === openIcon
    }

    /**
     * Prefers the VCS diff view — it's the more useful "what changed" surface — falling
     * back to opening the file when there's no [Change] to diff (untracked file, no VCS
     * root, or the [ChangeListManager] hasn't caught up yet). Deleted files have nothing
     * to open, so they simply do nothing in that fallback case.
     */
    private fun open(value: ChangedFile) {
        val change = changeFor(value)
        if (change != null) {
            ShowDiffAction.showDiffForChange(project, listOf(change))
        } else {
            openInEditor(value)
        }
    }

    private fun openInEditor(value: ChangedFile) {
        if (value.kind == ChangeKind.DELETED) return
        val file = LocalFileSystem.getInstance().findFileByPath(value.path) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun changeFor(value: ChangedFile): Change? {
        val changeListManager = ChangeListManager.getInstance(project)
        return if (value.kind == ChangeKind.DELETED) {
            val deletedPath = VcsContextFactory.getInstance().createFilePath(value.path, false)
            changeListManager.getChange(deletedPath)
        } else {
            val file = LocalFileSystem.getInstance().findFileByPath(value.path) ?: return null
            changeListManager.getChange(file)
        }
    }

    /** Brings up the non-modal Commit tool window, falling back to Version Control (e.g. modal commit mode). */
    private fun openCommitView() {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = manager.getToolWindow(ToolWindowId.COMMIT) ?: manager.getToolWindow(ToolWindowId.VCS)
        toolWindow?.activate(null)
    }

    private fun attributesFor(kind: ChangeKind, hovered: Boolean): SimpleTextAttributes {
        val style = when {
            kind == ChangeKind.DELETED -> SimpleTextAttributes.STYLE_STRIKEOUT
            hovered -> SimpleTextAttributes.STYLE_PLAIN or SimpleTextAttributes.STYLE_UNDERLINE
            else -> SimpleTextAttributes.STYLE_PLAIN
        }
        val color = when (kind) {
            ChangeKind.CREATED -> FileStatus.ADDED.color
            ChangeKind.MODIFIED -> FileStatus.MODIFIED.color
            ChangeKind.DELETED -> FileStatus.DELETED.color
        }
        return SimpleTextAttributes(style, color)
    }
}

/** Destructive-action red, tuned per theme: deeper on light backgrounds, softer on dark. */
private val CLEAR_ICON_COLOR = JBColor(0xCC3645, 0xE05050)
