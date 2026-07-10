package com.github.vladimirvaca.cliagentdock.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The merge rules decide what a session's changed-files list shows when several events
 * hit the same file. Pure logic, so tested without the platform fixture.
 */
class SessionFileChangeTrackerTest {

    @Test
    fun firstEventIsKeptAsIs() {
        assertEquals(ChangeKind.CREATED, SessionFileChangeTracker.merge(null, ChangeKind.CREATED))
        assertEquals(ChangeKind.MODIFIED, SessionFileChangeTracker.merge(null, ChangeKind.MODIFIED))
        assertEquals(ChangeKind.DELETED, SessionFileChangeTracker.merge(null, ChangeKind.DELETED))
    }

    @Test
    fun editingACreatedFileStaysCreated() {
        assertEquals(ChangeKind.CREATED, SessionFileChangeTracker.merge(ChangeKind.CREATED, ChangeKind.MODIFIED))
    }

    @Test
    fun deletingACreatedFileDropsTheEntry() {
        assertNull(SessionFileChangeTracker.merge(ChangeKind.CREATED, ChangeKind.DELETED))
    }

    @Test
    fun recreatingADeletedFileIsAModification() {
        assertEquals(ChangeKind.MODIFIED, SessionFileChangeTracker.merge(ChangeKind.DELETED, ChangeKind.CREATED))
    }

    @Test
    fun deletingAModifiedFileIsADeletion() {
        assertEquals(ChangeKind.DELETED, SessionFileChangeTracker.merge(ChangeKind.MODIFIED, ChangeKind.DELETED))
    }

    @Test
    fun repeatedModificationsStayModified() {
        assertEquals(ChangeKind.MODIFIED, SessionFileChangeTracker.merge(ChangeKind.MODIFIED, ChangeKind.MODIFIED))
    }
}
