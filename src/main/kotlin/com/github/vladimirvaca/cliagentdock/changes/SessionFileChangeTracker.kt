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
 * A change is **held back until the VCS has had its say** (see [pending]). A VFS event
 * alone cannot tell reviewable work from noise — a write to a git-ignored file, a file
 * rewritten with identical content, a temp file that is gone a moment later — and the
 * VCS would have such an entry pruned about a second after it was shown, which made
 * rows (and, when it was the only row, the whole panel) flash in and out.
 *
 * VFS events arrive through the project's [SessionChangeHub] — one listener however many
 * sessions are open — for as long as [parentDisposable] lives, so tracking stops with the
 * session (close or restart). [onChanged] receives the full cumulative snapshot after
 * changes, always on the EDT and never after disposal; a burst of changes is coalesced
 * into one call.
 */
class SessionFileChangeTracker(
    private val project: Project,
    basePath: String,
    parentDisposable: Disposable,
    private val onChanged: (List<ChangedFile>) -> Unit,
) {

    private val basePath = FileUtil.toSystemIndependentName(basePath)

    private val vcsContextFactory = VcsContextFactory.getInstance()

    /** True while an [onChanged] call is queued; see [fireChanged]. */
    private var notifyQueued = false

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

    /**
     * Recorded paths not reported to [onChanged] yet, with the `nanoTime` they were first
     * recorded. An entry leaves this map — becomes visible — once a VCS update confirms
     * it as a real change, when its path has no VCS at all, or after [PENDING_REVEAL_MS]
     * as a safety net for a VCS that isn't updating. An entry the VCS turns down instead
     * (ignored, or clean) is dropped without ever having been shown.
     */
    private val pending = HashMap<String, Long>()

    /**
     * Paths the VCS reported clean (or, for a deletion, unknown) once already, and that
     * were then marked dirty by [reconcileWithVcs] to get a verdict that is certain to
     * postdate the recorded change. The platform marks changed files dirty
     * asynchronously, so a VCS update finishing right after a VFS event can call a file
     * clean simply because it never looked at it; acting on that would lose the entry.
     */
    private val cleanVerified = HashSet<String>()

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
        SessionChangeHub.getInstance(project).register(this, parentDisposable)
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
            revealOverdue()
            scheduleRefreshNudge()
        }, REFRESH_NUDGE_MS)
    }

    /** Forgets everything recorded so far and notifies with an empty snapshot. */
    fun clear() {
        deletionsSeenByVcs.clear()
        pending.clear()
        cleanVerified.clear()
        if (changes.isEmpty()) return
        changes.clear()
        fireChanged()
    }

    /**
     * Runs after every [ChangeListManager] update. Fires even when nothing was pruned or
     * revealed — per-file VCS statuses feeding the panel (its "untracked" tags) may have
     * changed with this same update, e.g. after a `git add`; the panel skips a snapshot
     * that changes nothing.
     */
    private fun onVcsStateSettled() {
        if (changes.isEmpty()) return
        reconcileWithVcs()
        fireChanged()
    }

    /**
     * Reconciles the recorded changes with what the VCS now reports. Entries it shows as
     * changed are confirmed (a [pending] one becomes visible); entries it considers
     * ignored or clean leave the list — for a visible entry that is a commit or revert,
     * for a pending one it means there was never anything to review.
     */
    private fun reconcileWithVcs() {
        val changeListManager = ChangeListManager.getInstance(project)
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val fileSystem = LocalFileSystem.getInstance()

        val prunable = ArrayList<String>()
        val confirmed = ArrayList<String>()
        // No pending change in the VCS — but that only counts once the VCS has certainly
        // looked at the file after the change was recorded (see [cleanVerified]).
        val cleanLooking = ArrayList<Pair<ChangedFile, FilePath>>()

        for (entry in changes.values) {
            val filePath = vcsContextFactory.createFilePath(entry.path, false)
            // No VCS root here (or no VCS at all): nothing ever gets committed, keep it.
            if (vcsManager.getVcsFor(filePath) == null) {
                confirmed.add(entry.path)
                continue
            }
            if (entry.kind == ChangeKind.DELETED) {
                when {
                    changeListManager.getChange(filePath) != null -> {
                        deletionsSeenByVcs.add(entry.path)
                        confirmed.add(entry.path)
                    }
                    entry.path in deletionsSeenByVcs -> prunable.add(entry.path)
                    // A deletion the VCS has no change for: an untracked file, or the
                    // VCS hasn't caught up. Only a still-hidden entry needs that settled.
                    entry.path in pending -> cleanLooking.add(entry to filePath)
                }
            } else {
                val file = fileSystem.findFileByPath(entry.path) ?: continue
                when (changeListManager.getStatus(file)) {
                    // Ignored (the hub's check at event time is only approximate and
                    // knows nothing of .gitignore): not reviewable work, drop it.
                    FileStatus.IGNORED -> prunable.add(entry.path)
                    FileStatus.NOT_CHANGED -> cleanLooking.add(entry to filePath)
                    else -> {
                        confirmed.add(entry.path)
                        cleanVerified.remove(entry.path)
                    }
                }
            }
        }

        if (cleanLooking.isNotEmpty()) {
            val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
            val stillDirty = dirtyScopeManager.whatFilesDirty(cleanLooking.map { it.second }).toSet()
            val recheck = ArrayList<FilePath>()
            for ((entry, filePath) in cleanLooking) {
                when {
                    // Awaiting a VCS rescan: the update that just finished predates it.
                    filePath in stillDirty -> {}
                    // First clean verdict: ask for one that is sure to cover the change.
                    cleanVerified.add(entry.path) -> recheck.add(filePath)
                    // Second one. An untracked file's deletion stays as the session's
                    // record of it; anything else was committed, reverted or never differed.
                    entry.kind == ChangeKind.DELETED -> confirmed.add(entry.path)
                    else -> prunable.add(entry.path)
                }
            }
            if (recheck.isNotEmpty()) dirtyScopeManager.filePathsDirty(recheck, null)
        }

        for (path in prunable) forget(path)
        for (path in confirmed) pending.remove(path)
        if (prunable.isNotEmpty()) {
            thisLogger().info("Pruned ${prunable.size} entries the VCS reports clean or ignored")
        }
    }

    /** Reveals [pending] entries on paths no VCS covers: no verdict will ever come for them. */
    private fun revealUnversioned() {
        if (pending.isEmpty()) return
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        pending.keys.removeIf { vcsManager.getVcsFor(vcsContextFactory.createFilePath(it, false)) == null }
    }

    /**
     * Safety net, on the refresh tick: a [pending] entry the VCS hasn't ruled on within
     * [PENDING_REVEAL_MS] (updates paused during a long git operation, a huge repository)
     * is shown anyway — a late or flickering row beats a change that never appears.
     */
    private fun revealOverdue() {
        if (pending.isEmpty()) return
        val deadline = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(PENDING_REVEAL_MS.toLong())
        if (pending.values.removeIf { it <= deadline }) fireChanged()
    }

    private fun isShown(path: String): Boolean = path in changes && path !in pending

    /** Drops everything known about [path], returning the entry it had, if any. */
    private fun forget(path: String): ChangedFile? {
        pending.remove(path)
        cleanVerified.remove(path)
        deletionsSeenByVcs.remove(path)
        return changes.remove(path)
    }

    /**
     * Called by [SessionChangeHub] with one VFS batch, on the EDT inside a write action.
     * [isIgnored] is the hub's VCS-ignore check, memoized for the batch so several
     * sessions don't each pay for the same path.
     */
    internal fun onVfsEvents(events: List<VFileEvent>, isIgnored: (String) -> Boolean) {
        var updated = false
        for (event in events) {
            // Refresh events are how the file watcher reports external writes; everything
            // else (editor saves, IDE-side refactorings) is the user's own doing.
            if (!event.isFromRefresh) continue
            updated = apply(event, isIgnored) || updated
        }
        if (updated) fireChanged()
    }

    private fun apply(event: VFileEvent, isIgnored: (String) -> Boolean): Boolean = when (event) {
        is VFileCreateEvent ->
            !event.isDirectory && !predatesSession(event.file) && record(event.path, ChangeKind.CREATED, isIgnored)
        is VFileCopyEvent -> record(event.path, ChangeKind.CREATED, isIgnored)
        is VFileContentChangeEvent ->
            !predatesSession(event.file) && record(event.path, ChangeKind.MODIFIED, isIgnored)
        is VFileDeleteEvent -> !event.file.isDirectory && record(event.path, ChangeKind.DELETED, isIgnored)
        is VFileMoveEvent -> recordMoved(event.oldPath, event.path, isIgnored)
        is VFilePropertyChangeEvent ->
            event.propertyName == VirtualFile.PROP_NAME && recordMoved(event.oldPath, event.path, isIgnored)
        else -> false
    }

    private fun record(path: String, incoming: ChangeKind, isIgnored: (String) -> Boolean): Boolean {
        if (!isTracked(path, isIgnored)) return false
        val shown = isShown(path)
        val merged = merge(changes[path]?.kind, incoming)
        // Any earlier clean verdict is void now.
        cleanVerified.remove(path)
        if (merged == null) {
            forget(path)
            return true
        }
        changes[path] = ChangedFile(path, merged)
        if (!shown) pending.putIfAbsent(path, System.nanoTime())
        // A file that exists again (recreated after a delete) starts a fresh VCS story.
        if (merged != ChangeKind.DELETED) deletionsSeenByVcs.remove(path)
        return true
    }

    /** A rename/move shows up under the new path, keeping what we knew about the old one. */
    private fun recordMoved(oldPath: String, newPath: String, isIgnored: (String) -> Boolean): Boolean {
        // An entry already on screen stays on screen under its new path.
        val priorShown = isShown(oldPath)
        val prior = forget(oldPath)
        return when {
            isTracked(newPath, isIgnored) -> {
                val shown = priorShown || isShown(newPath)
                changes[newPath] = ChangedFile(newPath, prior?.kind ?: ChangeKind.MODIFIED)
                cleanVerified.remove(newPath)
                if (shown) pending.remove(newPath) else pending.putIfAbsent(newPath, System.nanoTime())
                true
            }
            // Moved out of the project: gone from the project's point of view, unless the
            // session itself created it (then created+removed nets out to nothing).
            isTracked(oldPath, isIgnored) && prior?.kind != ChangeKind.CREATED -> {
                changes[oldPath] = ChangedFile(oldPath, ChangeKind.DELETED)
                if (!priorShown) pending[oldPath] = System.nanoTime()
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

    /**
     * Inside the tracked root, not git bookkeeping, and not VCS-ignored. Ignored files
     * (build output, caches — whatever .gitignore and the IDE's ignore providers
     * cover) are side effects of the agent's work, not reviewable work, so they never
     * enter the list. The ignore check ([SessionChangeHub]) is fast-but-approximate by
     * contract; [reconcileWithVcs] holds the precise complement, dropping entries the VCS
     * itself later reports as ignored. It runs last, after the cheap string checks.
     */
    private fun isTracked(path: String, isIgnored: (String) -> Boolean): Boolean =
        FileUtil.isAncestor(basePath, path, true) &&
            !path.contains("/.git/") && !path.endsWith("/.git") &&
            !isIgnored(path)

    /**
     * Queues one [onChanged] call. VFS events arrive on the EDT inside a write action, so
     * the call hops out of it before touching Swing, and is dropped if the session was
     * disposed meanwhile. A refresh delivers a big change as many batches; while a call
     * is already queued further ones fold into it, and the snapshot is taken when it
     * runs, so the listener sees the latest state once instead of every step. Callers
     * are all on the EDT, so a plain flag is enough.
     */
    private fun fireChanged() {
        if (notifyQueued) return
        notifyQueued = true
        ApplicationManager.getApplication().invokeLater(
            {
                notifyQueued = false
                revealUnversioned()
                val snapshot = changes.values.filter { it.path !in pending }
                thisLogger().debug("Session changed files updated: ${snapshot.size} entries")
                onChanged(snapshot)
            },
            { disposed },
        )
    }

    companion object {
        /** How often to nudge a VFS refresh while the session runs. */
        private const val REFRESH_NUDGE_MS = 2_000

        /** How long a change waits for a VCS verdict before it is shown regardless. */
        private const val PENDING_REVEAL_MS = 5_000

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
            // A backgrounded IDE refreshes on frame activation anyway, so nudging it
            // only burns cycles on updates nobody sees; the next tick after focus
            // returns (≤ one interval) resumes live updates.
            if (!ApplicationManager.getApplication().isActive) return
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
