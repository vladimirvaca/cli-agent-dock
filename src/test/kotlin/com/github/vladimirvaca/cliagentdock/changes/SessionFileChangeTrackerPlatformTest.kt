package com.github.vladimirvaca.cliagentdock.changes

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import java.io.File

/**
 * End-to-end check of the tracker against the real local file system: writes performed
 * behind the IDE's back (like the agent CLI does) must surface as refresh events and be
 * recorded. A synchronous [com.intellij.openapi.vfs.VirtualFile.refresh] stands in for
 * the periodic refresh nudge the production tracker schedules.
 */
class SessionFileChangeTrackerPlatformTest : HeavyPlatformTestCase() {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = FileUtil.createTempDirectory("cliAgentDock", null)
        try {
            block(dir)
        } finally {
            FileUtil.delete(dir)
        }
    }

    /**
     * The tracker starts recording only after its baseline refresh (which absorbs changes
     * pending from before the session) completes; writes made before that are deliberately
     * invisible, so each test must wait for activation before changing files.
     */
    private fun trackActively(path: String, onChanged: (List<ChangedFile>) -> Unit) {
        val tracker = SessionFileChangeTracker(project, path, testRootDisposable, onChanged)
        PlatformTestUtil.waitWithEventsDispatching("tracker never became active", tracker::isActive, 30)
    }

    fun testExternalCreateIsRecordedAsCreated() = withTempDir { dir ->
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        vDir.children // cache the listing so the next refresh diffs against it

        var snapshot: List<ChangedFile>? = null
        trackActively(dir.path) { snapshot = it }

        val file = File(dir, "agent.txt").apply { writeText("hello") }
        vDir.refresh(false, true)
        UIUtil.dispatchAllInvocationEvents()

        val expected = ChangedFile(FileUtil.toSystemIndependentName(file.path), ChangeKind.CREATED)
        assertEquals(listOf(expected), snapshot)
    }

    fun testExternalModificationIsRecordedAsModified() = withTempDir { dir ->
        val file = File(dir, "existing.txt").apply { writeText("v1") }
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        vDir.children
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!

        var snapshot: List<ChangedFile>? = null
        trackActively(dir.path) { snapshot = it }

        file.writeText("v2, longer content")
        file.setLastModified(file.lastModified() + 2_000) // beat FS timestamp granularity
        vDir.refresh(false, true)
        UIUtil.dispatchAllInvocationEvents()

        val expected = ChangedFile(FileUtil.toSystemIndependentName(file.path), ChangeKind.MODIFIED)
        assertEquals(listOf(expected), snapshot)
    }

    fun testChangesPendingBeforeTheSessionAreNotAttributed() = withTempDir { dir ->
        val file = File(dir, "stale.txt").apply { writeText("v1") }
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        vDir.children
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!

        // Change the file behind the IDE's back *before* the session opens — like the
        // backlog the startup refresh replays for edits made while the IDE was closed.
        file.writeText("v2, longer content")
        file.setLastModified(file.lastModified() + 2_000)

        var snapshot: List<ChangedFile>? = null
        trackActively(dir.path) { snapshot = it }
        UIUtil.dispatchAllInvocationEvents()

        // The baseline refresh absorbed the pending change before recording started.
        assertNull(snapshot)
    }

    fun testChangesOutsideTheTrackedRootAreIgnored() = withTempDir { dir ->
        val tracked = File(dir, "tracked").apply { mkdirs() }
        val untracked = File(dir, "untracked").apply { mkdirs() }
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        vDir.children.forEach { it.children }

        var snapshot: List<ChangedFile>? = null
        trackActively(tracked.path) { snapshot = it }

        File(untracked, "elsewhere.txt").writeText("hello")
        vDir.refresh(false, true)
        UIUtil.dispatchAllInvocationEvents()

        assertNull(snapshot)
    }
}
