package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.domain.repository.LocationAccessRepository

/**
 * Keeps a running share in step with the per-contact grants.
 *
 * The granted set used to be read exactly once, at the moment the master switch went on, and never
 * observed again. Ticking a contact while sharing was already live therefore never started a share
 * for them, and — the half that matters — unticking one never stopped the share already running:
 * the user revoked access in the UI and their position kept flowing until the whole switch went off.
 *
 * Deliberately inert while nothing is being shared, so the picker keeps behaving as a selection
 * list before the switch is thrown rather than starting shares as each box is ticked.
 */
internal class LocationGrantReconciler(
    private val locationAccessRepository: LocationAccessRepository,
    private val target: Target,
) {
    /** The coordinator's side of the loop; it owns the share state this reads and acts on. */
    interface Target {
        /** Contacts we currently hold an outgoing share for. Empty means the switch is off. */
        fun sharedWith(): Set<String>
        suspend fun startSharingWith(contactIds: List<String>)

        /** Ends the share *and tells the peer*, unlike the silent block/delete teardown. */
        suspend fun revokeSharingWith(contactId: String)
    }

    suspend fun run() {
        locationAccessRepository.observeAll().collect { grants ->
            val current = target.sharedWith()
            if (current.isEmpty()) return@collect
            val granted = grants.filterValues { it }.keys
            val added = (granted - current).toList()
            val removed = current - granted
            if (added.isEmpty() && removed.isEmpty()) return@collect
            AppLogger.info("Location", "grants changed: +${added.size} -${removed.size}")
            if (added.isNotEmpty()) target.startSharingWith(added)
            for (contactId in removed) target.revokeSharingWith(contactId)
        }
    }
}
