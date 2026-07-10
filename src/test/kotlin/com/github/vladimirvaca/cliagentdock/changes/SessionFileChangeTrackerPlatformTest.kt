package com.github.vladimirvaca.cliagentdock.changes

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
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

    fun testExternalCreateIsRecordedAsCreated() = withTempDir { dir ->
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        vDir.children // cache the listing so the next refresh diffs against it

        var snapshot: List<ChangedFile>? = null
        SessionFileChangeTracker(dir.path, testRootDisposable) { snapshot = it }

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
        SessionFileChangeTracker(dir.path, testRootDisposable) { snapshot = it }

        file.writeText("v2, longer content")
        file.setLastModified(file.lastModified() + 2_000) // beat FS timestamp granularity
        vDir.refresh(false, true)
        UIUtil.dispatchAllInvocationEvents()

        val expected = ChangedFile(FileUtil.toSystemIndependentName(file.path), ChangeKind.MODIFIED)
        assertEquals(listOf(expected), snapshot)
    }

    fun testChangesOutsideTheTrackedRootAreIgnored() = withTempDir { dir ->
        val tracked = File(dir, "tracked").apply { mkdirs() }
        val untracked = File(dir, "untracked").apply { mkdirs() }
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        vDir.children.forEach { it.children }

        var snapshot: List<ChangedFile>? = null
        SessionFileChangeTracker(tracked.path, testRootDisposable) { snapshot = it }

        File(untracked, "elsewhere.txt").writeText("hello")
        vDir.refresh(false, true)
        UIUtil.dispatchAllInvocationEvents()

        assertNull(snapshot)
    }
}
