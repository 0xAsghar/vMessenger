package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.NetworkConfig
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
        // The node list is the whole truth: the app ships no relay of its own to fall back on.
        val ranked = nodeRepository.enabledRelayUrls()
        NetworkConfig.rankedRelayUrls = ranked
        val selected = ranked.firstOrNull()?.let(::SelectedRelay)
        NetworkConfig.relayAddress = selected?.url.orEmpty()
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
