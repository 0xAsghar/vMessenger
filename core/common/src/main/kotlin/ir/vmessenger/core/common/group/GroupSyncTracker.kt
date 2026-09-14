package ir.vmessenger.core.common.group

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Groups whose membership has stopped moving.
 *
 * Membership is creator-authoritative, so a member that missed one control can
 * only be repaired by a snapshot from the creator. If the creator never comes
 * back the group is frozen at its last version and the member keeps asking, with
 * nothing on screen to say why nobody's changes ever land. Each unanswered
 * request is counted here and, past [UNANSWERED_REQUESTS_BEFORE_ALERT], the group
 * is published so the conversation screen can say it out loud.
 *
 * In memory and process-wide on purpose: the count is about this run of the app,
 * and every restart is a fresh chance for the creator to answer.
 */
object GroupSyncTracker {
    /** Requests that have to go unanswered before the group is called out of sync. */
    const val UNANSWERED_REQUESTS_BEFORE_ALERT = 3

    private val unanswered = ConcurrentHashMap<String, Int>()

    private val _outOfSync = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the groups whose creator has stopped answering. */
    val outOfSync: StateFlow<Set<String>> = _outOfSync

    /** One more snapshot asked of a creator that has not answered the previous ones. */
    fun recordSnapshotRequest(groupId: String) {
        val asked = unanswered.merge(groupId, 1, Int::plus) ?: 1
        if (asked >= UNANSWERED_REQUESTS_BEFORE_ALERT) {
            _outOfSync.update { current -> if (groupId in current) current else current + groupId }
        }
    }

    /** A snapshot landed: the group is whole again and the count starts over. */
    fun recordSnapshotApplied(groupId: String) {
        unanswered.remove(groupId)
        _outOfSync.update { current -> if (groupId in current) current - groupId else current }
    }

    fun clear() {
        unanswered.clear()
        _outOfSync.value = emptySet()
    }
}
