package com.github.vladimirvaca.cliagentdock.changes

import com.github.vladimirvaca.cliagentdock.CliAgentDockBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.SideBorder
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Color
import java.awt.Cursor
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JList

/**
 * The "Files changed" strip shown below a session's terminal, listing what the agent
 * touched (see [SessionFileChangeTracker]). Rows use the IDE's VCS colors — green
 * created, blue modified, struck-through gray deleted — and behave like hyperlinks:
 * hovering highlights and underlines a row (hand cursor), a single click opens the file
 * in the editor. Deleted rows are inert. The header offers a shortcut to the IDE's
 * commit view; a red clear button sits in the footer's bottom-right corner, apart from
 * the other actions since it's destructive. The owner shows/hides the whole panel, so it
 * renders assuming content.
 */
class ChangedFilesPanel(
    private val project: Project,
    basePath: String,
    onClear: () -> Unit,
) : BorderLayoutPanel() {

    private val basePath = FileUtil.toSystemIndependentName(basePath)
    private val model = CollectionListModel<ChangedFile>()
    private val list = JBList(model)
    private val countLabel = JBLabel()
    private var rawCountText = ""
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

    init {
        list.cellRenderer = object : ColoredListCellRenderer<ChangedFile>() {
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
                val hovered = index == hoveredIndex && value.kind != ChangeKind.DELETED
                if (hovered && !selected) {
                    background = UIUtil.getListSelectionBackground(false)
                }
                append(relative, attributesFor(value.kind, hovered))
            }
        }

        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                rowAt(e)?.let { open(model.getElementAt(it)) }
            }

            override fun mouseMoved(e: MouseEvent) {
                val row = rowAt(e)
                setHovered(row ?: -1)
                val clickable = row != null && model.getElementAt(row).kind != ChangeKind.DELETED
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
            override fun mouseEntered(e: MouseEvent) {
                countLabel.text = "<html><u>$rawCountText</u></html>"
            }
            override fun mouseExited(e: MouseEvent) {
                countLabel.text = rawCountText
            }
        })

        val commitButton = InplaceButton(
            IconButton(CliAgentDockBundle["changedFiles.openCommit.tooltip"], AllIcons.Actions.Commit),
        ) { openCommitView() }

        val clearButton = InplaceButton(
            IconButton(CliAgentDockBundle["changedFiles.clear.tooltip"], tintedIcon(AllIcons.Actions.GC, CLEAR_ICON_COLOR)),
        ) { onClear() }

        val left = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(countLabel)
            add(commitButton)
        }

        val header = BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 2, 8)
            addToLeft(left)
            addToRight(minimizeButton)
        }

        // Clear is destructive, so it sits apart from the header actions, anchored to the
        // panel's bottom-right corner where it can't be brushed against by accident.
        val footer = BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 8, 6, 8)
            addToRight(clearButton)
        }

        addToTop(header)
        addToCenter(scroll)
        addToBottom(footer)

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
        rawCountText = CliAgentDockBundle["changedFiles.header", changes.size]
        countLabel.text = rawCountText
    }

    /** The row whose cell actually contains the event point, or null (list may have dead space). */
    private fun rowAt(e: MouseEvent): Int? {
        val index = list.locationToIndex(e.point)
        if (index < 0 || !list.getCellBounds(index, index).contains(e.point)) return null
        return index
    }

    private fun setHovered(index: Int) {
        if (hoveredIndex == index) return
        hoveredIndex = index
        list.repaint()
    }

    private fun open(value: ChangedFile) {
        if (value.kind == ChangeKind.DELETED) return
        val file = LocalFileSystem.getInstance().findFileByPath(value.path) ?: return
        OpenFileDescriptor(project, file).navigate(true)
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

/** Flat red used for the destructive clear action; deliberately theme-invariant, not JBColor. */
private val CLEAR_ICON_COLOR = Color(0xE0, 0x50, 0x50)

/** Recolors [source] to a flat [color] silhouette, keeping its original alpha/anti-aliasing. */
private fun tintedIcon(source: Icon, color: Color): Icon {
    val scale = 4
    val width = source.iconWidth
    val height = source.iconHeight
    val buffer = BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB)
    val g2 = buffer.createGraphics()
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.scale(scale.toDouble(), scale.toDouble())
    source.paintIcon(null, g2, 0, 0)
    g2.dispose()

    val rgb = color.rgb and 0x00FFFFFF
    for (py in 0 until buffer.height) {
        for (px in 0 until buffer.width) {
            val alpha = buffer.getRGB(px, py) ushr 24
            if (alpha != 0) buffer.setRGB(px, py, (alpha shl 24) or rgb)
        }
    }
    return ImageIcon(buffer.getScaledInstance(width, height, Image.SCALE_SMOOTH))
}
