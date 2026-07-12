package com.github.vladimirvaca.cliagentdock.changes

import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
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
 * editor changes arrive as document saves and are therefore excluded. The cost of that
 * simplicity is attribution granularity: any external writer during the session (another
 * agent tab, a `git pull` in another terminal) is counted too.
 *
 * The listener lives on the application message bus scoped to [parentDisposable], so it
 * stops with the session (close or restart). [onChanged] receives the full cumulative
 * snapshot after every change, always on the EDT and never after disposal.
 */
class SessionFileChangeTracker(
    basePath: String,
    parentDisposable: Disposable,
    private val onChanged: (List<ChangedFile>) -> Unit,
) : BulkFileListener {

    private val basePath = FileUtil.toSystemIndependentName(basePath)

    @Volatile
    private var disposed = false

    /** True once the baseline refresh completed and external changes are being recorded. */
    @Volatile
    var isActive: Boolean = false
        private set

    /** Keyed by path so repeated events on one file collapse into a single entry. */
    private val changes = LinkedHashMap<String, ChangedFile>()

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
        if (changes.isEmpty()) return
        changes.clear()
        fireChanged()
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
        is VFileCreateEvent -> !event.isDirectory && record(event.path, ChangeKind.CREATED)
        is VFileCopyEvent -> record(event.path, ChangeKind.CREATED)
        is VFileContentChangeEvent -> record(event.path, ChangeKind.MODIFIED)
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
