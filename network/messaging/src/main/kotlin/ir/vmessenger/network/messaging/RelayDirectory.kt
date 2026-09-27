package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.network.SelectedRelay

/**
 * Supplies the relay endpoint the listener should currently use and records the
 * outcome of relay connections. Backed by a health-ranked list of relay nodes so
 * the app can rotate away from failing relays instead of depending on a single
 * hardcoded one.
 *
 * The implementation lives in the data layer (DB-backed). Every relay in it is one the person added or
 * set up; the app ships none of its own, so with every relay switched off there is none.
 */
interface RelayDirectory {
    /**
     * The relay URL to connect through right now (the healthiest enabled relay), or null when no relay
     * is enabled: the person has not added one yet, or switched them all off.
     */
    suspend fun activeRelay(): SelectedRelay?

    /** Last relay returned by [activeRelay]; useful for publish/listener alignment tests. */
    fun lastSelectedRelay(): SelectedRelay?

    /** Records whether a connection to [url] succeeded, updating health ranking. */
    suspend fun reportResult(url: String, ok: Boolean)
}
