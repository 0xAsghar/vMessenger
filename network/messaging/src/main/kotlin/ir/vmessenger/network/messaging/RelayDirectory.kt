package ir.vmessenger.network.messaging

/**
 * Supplies the relay endpoint the listener should currently use and records the
 * outcome of relay connections. Backed by a health-ranked list of relay nodes so
 * the app can rotate away from failing relays instead of depending on a single
 * hardcoded one (see docs/P2P-Phases.md Phase 1).
 *
 * The implementation lives in the data layer (DB-backed). The default fallback
 * remains available because the built-in relay is always seeded as one entry.
 */
interface RelayDirectory {
    /** The relay URL to connect through right now (healthiest enabled relay). */
    suspend fun activeRelayUrl(): String

    /** Records whether a connection to [url] succeeded, updating health ranking. */
    suspend fun reportResult(url: String, ok: Boolean)
}
