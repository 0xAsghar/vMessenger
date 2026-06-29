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

    override suspend fun activeRelay(): SelectedRelay {
        val default = NetworkConfig.DEFAULT_RELAY_URL
        val ranked = if (P2PConfig.multiNodeEnabled) nodeRepository.enabledRelayUrls() else emptyList()
        NetworkConfig.rankedRelayUrls = ranked
        val url = selectActiveRelay(
            rankedRelays = ranked,
            default = default,
        )
        NetworkConfig.relayAddress = url
        val selected = SelectedRelay(
            url = url,
            source = if (url == default) RelaySource.DEFAULT else RelaySource.RANKED,
        )
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
 * Picks the relay to use: the healthiest enabled relay, or the built-in default
 * when none are available. Pure so it can be unit-tested independently of the DB.
 */
fun selectActiveRelay(rankedRelays: List<String>, default: String): String =
    rankedRelays.firstOrNull() ?: default
