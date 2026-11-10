package com.github.vladimirvaca.cliagentdock.changes

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.actions.VcsContextFactory
import com.intellij.openapi.vcs.changes.VcsIgnoreManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * The project's single VFS listener for session change tracking, fanning each batch out
 * to the live [SessionFileChangeTracker]s.
 *
 * VFS events are delivered on the EDT inside a write action, so their handling is a hot
 * path, and the costly part of it — asking the VCS whether a path is ignored — does not
 * depend on which session is asking. With a listener per session, a branch switch or an
 * `npm install` made every open tab repeat that check for every file. Here it is
 * answered once per path per batch and shared.
 *
 * Subscribes on the first registration, so a project that never opens the dock pays
 * nothing. Trackers register and unregister on the EDT, which is also where batches
 * arrive, so the list needs no synchronization.
 */
@Service(Service.Level.PROJECT)
internal class SessionChangeHub(project: Project) : BulkFileListener, Disposable {

    private val vcsIgnoreManager = VcsIgnoreManager.getInstance(project)
    private val vcsContextFactory = VcsContextFactory.getInstance()

    private val trackers = ArrayList<SessionFileChangeTracker>()
    private var subscribed = false

    fun register(tracker: SessionFileChangeTracker, parentDisposable: Disposable) {
        if (!subscribed) {
            subscribed = true
            ApplicationManager.getApplication().messageBus
                .connect(this)
                .subscribe(VirtualFileManager.VFS_CHANGES, this)
        }
        trackers.add(tracker)
        Disposer.register(parentDisposable) { trackers.remove(tracker) }
    }

    override fun after(events: List<VFileEvent>) {
        if (trackers.isEmpty()) return
        // Fast-but-approximate by contract. It works on a bare path, which matters here:
        // at event time a created file may not be in the VFS yet and a deleted one is
        // already gone.
        val verdicts = HashMap<String, Boolean>()
        val isIgnored = { path: String ->
            verdicts.getOrPut(path) {
                vcsIgnoreManager.isPotentiallyIgnoredFile(vcsContextFactory.createFilePath(path, false))
            }
        }
        // Iterate a copy so nothing a tracker triggers can disturb the loop.
        for (tracker in trackers.toTypedArray()) {
            tracker.onVfsEvents(events, isIgnored)
        }
    }

    override fun dispose() {
        trackers.clear()
    }

    companion object {
        fun getInstance(project: Project): SessionChangeHub = project.service()
    }
}
