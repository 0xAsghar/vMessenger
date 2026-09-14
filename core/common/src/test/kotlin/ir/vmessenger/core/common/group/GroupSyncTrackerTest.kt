package ir.vmessenger.core.common.group

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroupSyncTrackerTest {
    @Before
    fun reset() = GroupSyncTracker.clear()

    @After
    fun tearDown() = GroupSyncTracker.clear()

    @Test
    fun oneUnansweredRequestIsNotYetAProblem() {
        GroupSyncTracker.recordSnapshotRequest(GROUP)

        assertTrue(GroupSyncTracker.outOfSync.value.isEmpty())
    }

    @Test
    fun theGroupIsCalledOutOfSyncOnceTheCreatorHasIgnoredEnoughRequests() {
        repeat(GroupSyncTracker.UNANSWERED_REQUESTS_BEFORE_ALERT) { GroupSyncTracker.recordSnapshotRequest(GROUP) }

        assertEquals(setOf(GROUP), GroupSyncTracker.outOfSync.value)
    }

    @Test
    fun aSnapshotThatLandsClearsTheGroupAndTheCount() {
        repeat(GroupSyncTracker.UNANSWERED_REQUESTS_BEFORE_ALERT) { GroupSyncTracker.recordSnapshotRequest(GROUP) }
        GroupSyncTracker.recordSnapshotApplied(GROUP)
        assertTrue(GroupSyncTracker.outOfSync.value.isEmpty())

        // And the count starts over rather than tripping on the next single ask.
        GroupSyncTracker.recordSnapshotRequest(GROUP)
        assertTrue(GroupSyncTracker.outOfSync.value.isEmpty())
    }

    @Test
    fun groupsAreCountedApart() {
        repeat(GroupSyncTracker.UNANSWERED_REQUESTS_BEFORE_ALERT) { GroupSyncTracker.recordSnapshotRequest(GROUP) }
        GroupSyncTracker.recordSnapshotRequest(OTHER_GROUP)

        assertTrue(GROUP in GroupSyncTracker.outOfSync.value)
        assertFalse(OTHER_GROUP in GroupSyncTracker.outOfSync.value)
    }

    private companion object {
        const val GROUP = "a1b2"
        const val OTHER_GROUP = "c3d4"
    }
}
