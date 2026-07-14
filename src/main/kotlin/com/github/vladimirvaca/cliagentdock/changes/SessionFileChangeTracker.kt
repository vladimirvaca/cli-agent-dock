package com.github.vladimirvaca.cliagentdock.changes

import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.actions.VcsContextFactory
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** How a file changed during the session, from the project's point of view. */
enum class ChangeKind { CREATED, MODIFIED, DELETED }

/** A file changed during the session. [path] is absolute and system-independent ('/'). */
data class ChangedFile(val path: String, val kind: ChangeKind)

/**
 * Accumulates the files changed under [basePath] by external processes — in practice the
 * agent CLI running in the session's terminal — for as long as the session lives.
 *
 * Detection is VFS-based and agent-agnostic: the platform's native file watcher surfaces
 * external writes as refresh events ([VFileEvent.isFromRefresh]), while the user's own
 * editor changes arrive as document saves and are therefore excluded. Refresh also
 * *discovers* files it never loaded before (a directory first scanned mid-session) and
 * reports them with the same events an external write produces; those are told apart by
 * their on-disk timestamp — content older than the session start cannot be the session's
 * work and is ignored (see [predatesSession]). The remaining cost of this simplicity is
 * attribution granularity: any external writer during the session (another agent tab, a
 * `git pull` in another terminal) is counted too.
 *
 * The list also empties itself as the work lands in the VCS: after every
 * [ChangeListManager] update, entries the VCS reports clean again — which is what a
 * commit (or revert) looks like from here — are pruned, so committing the session's
 * changes clears the panel without anyone pressing the clear button.
 *
 * The listener lives on the application message bus scoped to [parentDisposable], so it
 * stops with the session (close or restart). [onChanged] receives the full cumulative
 * snapshot after every change, always on the EDT and never after disposal.
 */
class SessionFileChangeTracker(
    private val project: Project,
    basePath: String,
    parentDisposable: Disposable,
    private val onChanged: (List<ChangedFile>) -> Unit,
) : BulkFileListener {

    private val basePath = FileUtil.toSystemIndependentName(basePath)

    /** Wall-clock start of the session; anything on disk older than this predates it. */
    private val sessionStartMillis = System.currentTimeMillis()

    @Volatile
    private var disposed = false

    /** True once the baseline refresh completed and external changes are being recorded. */
    @Volatile
    var isActive: Boolean = false
        private set

    /** Keyed by path so repeated events on one file collapse into a single entry. */
    private val changes = LinkedHashMap<String, ChangedFile>()

    /**
     * Deleted paths the [ChangeListManager] has shown a pending change for at some point.
     * Only those may be pruned when their change later disappears (= the deletion was
     * committed); a deletion the VCS never saw (an untracked file) has no commit to wait
     * for and stays listed as the session's record of it.
     */
    private val deletionsSeenByVcs = HashSet<String>()

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    init {
        Disposer.register(parentDisposable) { disposed = true }
        startAfterBaselineRefresh(parentDisposable)
    }

    /**
     * Changes made while the IDE was closed surface as refresh events too, on the first
     * refresh after startup — recording from the moment of construction would attribute
     * that backlog to a session that did nothing yet. So the tracked root is refreshed
     * first, flushing any pending external changes into the VFS, and the listener only
     * subscribes once that baseline pass completes: every session starts out clean.
     */
    private fun startAfterBaselineRefresh(parentDisposable: Disposable) {
        val root = LocalFileSystem.getInstance().findFileByPath(basePath)
        if (root == null) {
            start(parentDisposable)
            return
        }
        RefreshQueue.getInstance().refresh(true, true, { start(parentDisposable) }, root)
    }

    /** Runs on the EDT (refresh finish action), as does disposal, so the guard is race-free. */
    private fun start(parentDisposable: Disposable) {
        if (disposed) return
        ApplicationManager.getApplication().messageBus
            .connect(parentDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, this)
        // changeListUpdateDone fires (on the CLM update thread) after the VCS finished
        // rescanning — the moment a commit's effect is actually visible to us.
        ChangeListManager.getInstance(project).addChangeListListener(
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    ApplicationManager.getApplication().invokeLater({ onVcsStateSettled() }, { disposed })
                }
            },
            parentDisposable,
        )
        isActive = true
        thisLogger().info("Tracking external file changes under $basePath")
        scheduleRefreshNudge()
    }

    /**
     * The file watcher only marks paths dirty; the platform turns them into VFS events when
     * a refresh runs, and its usual triggers (frame/editor activation) never fire while the
     * user stays focused on the embedded terminal. This nudge keeps refreshes coming during
     * the session. Cheap: with a working watcher only dirty paths are rescanned, and the
     * alarm dies with the session's disposable.
     */
    private fun scheduleRefreshNudge() {
        refreshAlarm.addRequest({
            nudgeIfDue()
            scheduleRefreshNudge()
        }, REFRESH_NUDGE_MS)
    }

    /** Forgets everything recorded so far and notifies with an empty snapshot. */
    fun clear() {
        deletionsSeenByVcs.clear()
        if (changes.isEmpty()) return
        changes.clear()
        fireChanged()
    }

    /**
     * Reconciles the recorded changes with what the VCS now reports: entries it considers
     * clean again were committed (or reverted) and leave the list. Fires even when nothing
     * was pruned — per-file VCS statuses feeding the panel (its "untracked" tags) may have
     * changed with this same update, e.g. after a `git add`.
     */
    private fun onVcsStateSettled() {
        if (changes.isEmpty()) return
        pruneCommitted()
        fireChanged()
    }

    private fun pruneCommitted() {
        val changeListManager = ChangeListManager.getInstance(project)
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val contextFactory = VcsContextFactory.getInstance()
        val fileSystem = LocalFileSystem.getInstance()

        val prunable = ArrayList<String>()
        // Clean-looking files still awaiting a VCS rescan must not be pruned yet: their
        // change was recorded, but the CLM update that just finished predates it.
        val cleanLooking = ArrayList<Pair<String, FilePath>>()

        for (entry in changes.values) {
            val filePath = contextFactory.createFilePath(entry.path, false)
            // No VCS root here (or no VCS at all): nothing ever gets committed, keep it.
            if (vcsManager.getVcsFor(filePath) == null) continue
            if (entry.kind == ChangeKind.DELETED) {
                if (changeListManager.getChange(filePath) != null) {
                    deletionsSeenByVcs.add(entry.path)
                } else if (entry.path in deletionsSeenByVcs) {
                    prunable.add(entry.path)
                }
            } else {
                val file = fileSystem.findFileByPath(entry.path) ?: continue
                if (changeListManager.getStatus(file) == FileStatus.NOT_CHANGED) {
                    cleanLooking.add(entry.path to filePath)
                }
            }
        }

        if (cleanLooking.isNotEmpty()) {
            val stillDirty = VcsDirtyScopeManager.getInstance(project)
                .whatFilesDirty(cleanLooking.map { it.second })
                .toSet()
            cleanLooking.filter { it.second !in stillDirty }.mapTo(prunable) { it.first }
        }

        for (path in prunable) {
            changes.remove(path)
            deletionsSeenByVcs.remove(path)
        }
        if (prunable.isNotEmpty()) {
            thisLogger().info("Pruned ${prunable.size} committed entr${if (prunable.size == 1) "y" else "ies"}")
        }
    }

    override fun after(events: List<VFileEvent>) {
        var updated = false
        for (event in events) {
            // Refresh events are how the file watcher reports external writes; everything
            // else (editor saves, IDE-side refactorings) is the user's own doing.
            if (!event.isFromRefresh) continue
            updated = apply(event) || updated
        }
        if (updated) fireChanged()
    }

    private fun apply(event: VFileEvent): Boolean = when (event) {
        is VFileCreateEvent ->
            !event.isDirectory && !predatesSession(event.file) && record(event.path, ChangeKind.CREATED)
        is VFileCopyEvent -> record(event.path, ChangeKind.CREATED)
        is VFileContentChangeEvent ->
            !predatesSession(event.file) && record(event.path, ChangeKind.MODIFIED)
        is VFileDeleteEvent -> !event.file.isDirectory && record(event.path, ChangeKind.DELETED)
        is VFileMoveEvent -> recordMoved(event.oldPath, event.path)
        is VFilePropertyChangeEvent ->
            event.propertyName == VirtualFile.PROP_NAME && recordMoved(event.oldPath, event.path)
        else -> false
    }

    private fun record(path: String, incoming: ChangeKind): Boolean {
        if (!isTracked(path)) return false
        val merged = merge(changes[path]?.kind, incoming)
        if (merged == null) changes.remove(path) else changes[path] = ChangedFile(path, merged)
        // A file that exists again (recreated after a delete) starts a fresh VCS story.
        if (merged != ChangeKind.DELETED) deletionsSeenByVcs.remove(path)
        return true
    }

    /** A rename/move shows up under the new path, keeping what we knew about the old one. */
    private fun recordMoved(oldPath: String, newPath: String): Boolean {
        val prior = changes.remove(oldPath)
        return when {
            isTracked(newPath) -> {
                changes[newPath] = ChangedFile(newPath, prior?.kind ?: ChangeKind.MODIFIED)
                true
            }
            // Moved out of the project: gone from the project's point of view, unless the
            // session itself created it (then created+removed nets out to nothing).
            isTracked(oldPath) && prior?.kind != ChangeKind.CREATED -> {
                changes[oldPath] = ChangedFile(oldPath, ChangeKind.DELETED)
                true
            }
            else -> prior != null
        }
    }

    /**
     * True when [file]'s on-disk timestamp clearly predates the session. Refresh events
     * don't only report new writes: the VFS surfaces files it never loaded before (a
     * directory first scanned mid-session by indexing, git, or the refresh nudge) as the
     * same "create" events an external write produces. Content older than the session
     * start cannot be this session's work, so such discoveries are dropped rather than
     * attributed to the agent. [TIMESTAMP_SLACK_MS] absorbs coarse file-system timestamp
     * granularity (FAT rounds down to 2s), which could otherwise make a file the agent
     * wrote moments after the session opened look older than the session.
     */
    private fun predatesSession(file: VirtualFile?): Boolean {
        val timeStamp = file?.timeStamp ?: return false
        return timeStamp < sessionStartMillis - TIMESTAMP_SLACK_MS
    }

    private fun isTracked(path: String): Boolean =
        FileUtil.isAncestor(basePath, path, true) &&
            !path.contains("/.git/") && !path.endsWith("/.git")

    private fun fireChanged() {
        val snapshot = changes.values.toList()
        thisLogger().debug("Session changed files updated: ${snapshot.size} entries")
        // VFS events arrive on the EDT inside a write action; hop out of it before
        // touching Swing, and drop the update if the session was disposed meanwhile.
        ApplicationManager.getApplication().invokeLater(
            { onChanged(snapshot) },
            { disposed },
        )
    }

    companion object {
        /** How often to nudge a VFS refresh while the session runs. */
        private const val REFRESH_NUDGE_MS = 2_000

        /** Tolerance for file-system timestamp granularity in [predatesSession]. */
        private const val TIMESTAMP_SLACK_MS = 2_000

        /**
         * Alarm-timer jitter would otherwise let a lone tracker's own next tick arrive a
         * hair "early" and be skipped, halving its effective cadence.
         */
        private const val NUDGE_JITTER_TOLERANCE_MS = 250

        /** When the last nudge fired, across all live trackers. */
        private val lastNudgeNanos = AtomicLong(Long.MIN_VALUE)

        /**
         * Every tracker ticks its own alarm, but one refresh per interval serves them
         * all: the refresh rescans everything dirty, regardless of who asked. This gate
         * keeps N open sessions from queueing N refreshes per interval.
         */
        private fun nudgeIfDue() {
            val now = System.nanoTime()
            val last = lastNudgeNanos.get()
            val due = last == Long.MIN_VALUE ||
                now - last >= TimeUnit.MILLISECONDS.toNanos((REFRESH_NUDGE_MS - NUDGE_JITTER_TOLERANCE_MS).toLong())
            if (due && lastNudgeNanos.compareAndSet(last, now)) {
                SaveAndSyncHandler.getInstance().scheduleRefresh()
            }
        }

        /**
         * Folds a new event into what the session already recorded for the same file.
         * Returns the kind to keep, or null to drop the entry entirely (a file both
         * created and deleted within the session never really existed for the user).
         */
        internal fun merge(previous: ChangeKind?, incoming: ChangeKind): ChangeKind? = when (incoming) {
            // Recreating a file the session deleted is, net, a modification.
            ChangeKind.CREATED -> if (previous == ChangeKind.DELETED) ChangeKind.MODIFIED else ChangeKind.CREATED
            // Edits to a file the session created keep it "created".
            ChangeKind.MODIFIED -> if (previous == ChangeKind.CREATED) ChangeKind.CREATED else ChangeKind.MODIFIED
            ChangeKind.DELETED -> if (previous == ChangeKind.CREATED) null else ChangeKind.DELETED
        }
    }
}
