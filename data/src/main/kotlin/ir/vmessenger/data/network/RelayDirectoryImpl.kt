package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.RelaySource
import ir.vmessenger.core.common.network.SelectedRelay
import ir.vmessenger.network.messaging.RelayDirectory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DB-backed [RelayDirectory]. Picks the healthiest enabled relay and exposes the
 * selection explicitly so publish and listener use the same endpoint.
 */
@Singleton
class RelayDirectoryImpl @Inject constructor(
    private val nodeRepository: NetworkNodeRepository,
) : RelayDirectory {
    @Volatile
    private var lastSelected: SelectedRelay? = null

    override suspend fun activeRelay(): SelectedRelay? {
        val default = NetworkConfig.DEFAULT_RELAY_URL
        val ranked = if (P2PConfig.multiNodeEnabled) nodeRepository.enabledRelayUrls() else emptyList()
        NetworkConfig.rankedRelayUrls = ranked
        // The node list is the whole truth: the built-in relay is one row in it, seeded unless the
        // person declined it, and switching it off has to mean it. Only the legacy single-node mode
        // (multi-node off) still falls back to it.
        val url = selectActiveRelay(
            rankedRelays = ranked,
            fallback = default.takeUnless { P2PConfig.multiNodeEnabled },
        )
        NetworkConfig.relayAddress = url.orEmpty()
        val selected = url?.let {
            SelectedRelay(url = it, source = if (it == default) RelaySource.DEFAULT else RelaySource.RANKED)
        }
        lastSelected = selected
        return selected
    }

    override fun lastSelectedRelay(): SelectedRelay? = lastSelected

    override suspend fun reportResult(url: String, ok: Boolean) {
        nodeRepository.recordRelayResult(url, ok)
        if (ok) {
            nodeRepository.promoteNodeOnSuccess(url)
        }
    }
}

/**
 * Picks the relay to use: the healthiest enabled relay, else [fallback] (null: no relay at all).
 * Pure so it can be unit-tested independently of the DB.
 */
fun selectActiveRelay(rankedRelays: List<String>, fallback: String?): String? =
    rankedRelays.firstOrNull() ?: fallback
