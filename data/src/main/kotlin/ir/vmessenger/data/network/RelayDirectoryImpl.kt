package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.network.messaging.RelayDirectory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DB-backed [RelayDirectory]. Picks the healthiest enabled relay and keeps
 * [NetworkConfig.relayAddress] in sync so the endpoint we publish matches the
 * relay we are actually listening on. Falls back to the built-in default relay
 * whenever the list is empty (docs/P2P-Phases.md Phase 1).
 */
@Singleton
class RelayDirectoryImpl @Inject constructor(
    private val nodeRepository: NetworkNodeRepository,
) : RelayDirectory {
    override suspend fun activeRelayUrl(): String {
        val url = selectActiveRelay(
            rankedRelays = if (P2PConfig.multiNodeEnabled) nodeRepository.enabledRelayUrls() else emptyList(),
            default = NetworkConfig.DEFAULT_RELAY_URL,
        )
        NetworkConfig.relayAddress = url
        return url
    }

    override suspend fun reportResult(url: String, ok: Boolean) {
        nodeRepository.recordRelayResult(url, ok)
    }
}

/**
 * Picks the relay to use: the healthiest enabled relay, or the built-in default
 * when none are available. Pure so it can be unit-tested independently of the DB.
 */
fun selectActiveRelay(rankedRelays: List<String>, default: String): String =
    rankedRelays.firstOrNull() ?: default
