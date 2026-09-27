package ir.vmessenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.network.P2PConfig
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.p2pDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_p2p",
)

/**
 * Persisted P2P flags. Defaults mirror [P2PConfig] (the process-wide runtime
 * copy) and [P2PPreferences.P2P_DEFAULT_PEER_CACHE] & co.; all three must agree.
 */
data class P2PFlagSnapshot(
    val peerCacheEnabled: Boolean = P2PPreferences.P2P_DEFAULT_PEER_CACHE,
    val peerExchangeEnabled: Boolean = P2PPreferences.P2P_DEFAULT_PEER_EXCHANGE,
    val dhtParticipationEnabled: Boolean = P2PPreferences.P2P_DEFAULT_DHT,
    val relayPeerModeEnabled: Boolean = P2PPreferences.P2P_DEFAULT_RELAY_PEER,
    val natTraversalEnabled: Boolean = P2PPreferences.P2P_DEFAULT_NAT,
    val storeAndForwardEnabled: Boolean = P2PPreferences.P2P_DEFAULT_STORE_FORWARD,
)

@Singleton
class P2PPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun load(): P2PFlagSnapshot {
        val preferences = context.p2pDataStore.data.first()
        return P2PFlagSnapshot(
            peerCacheEnabled = preferences[KEY_PEER_CACHE] ?: P2P_DEFAULT_PEER_CACHE,
            peerExchangeEnabled = preferences[KEY_PEER_EXCHANGE] ?: P2P_DEFAULT_PEER_EXCHANGE,
            dhtParticipationEnabled = preferences[KEY_DHT] ?: P2P_DEFAULT_DHT,
            relayPeerModeEnabled = preferences[KEY_RELAY_PEER] ?: P2P_DEFAULT_RELAY_PEER,
            natTraversalEnabled = preferences[KEY_NAT] ?: P2P_DEFAULT_NAT,
            storeAndForwardEnabled = preferences[KEY_STORE_FORWARD] ?: P2P_DEFAULT_STORE_FORWARD,
        )
    }

    suspend fun save(snapshot: P2PFlagSnapshot) {
        context.p2pDataStore.edit { preferences ->
            preferences[KEY_PEER_CACHE] = snapshot.peerCacheEnabled
            preferences[KEY_PEER_EXCHANGE] = snapshot.peerExchangeEnabled
            preferences[KEY_DHT] = snapshot.dhtParticipationEnabled
            preferences[KEY_RELAY_PEER] = snapshot.relayPeerModeEnabled
            preferences[KEY_NAT] = snapshot.natTraversalEnabled
            preferences[KEY_STORE_FORWARD] = snapshot.storeAndForwardEnabled
            // Flags removed in 2.2.2 with the built-in node; drop what an older version stored.
            preferences.remove(KEY_REMOVED_MULTI_NODE)
            preferences.remove(KEY_REMOVED_REDUCE_RELAY)
        }
    }

    suspend fun resetToDefaults() {
        save(P2PFlagSnapshot())
    }

    /** Drops every stored flag (secure wipe); later reads fall back to the defaults. */
    suspend fun clear() {
        context.p2pDataStore.edit { it.clear() }
    }

    companion object {
        // Peer cache and store-and-forward on, every other experimental path off.
        const val P2P_DEFAULT_PEER_CACHE = P2PConfig.DEFAULT_PEER_CACHE
        const val P2P_DEFAULT_PEER_EXCHANGE = P2PConfig.DEFAULT_PEER_EXCHANGE
        const val P2P_DEFAULT_DHT = P2PConfig.DEFAULT_DHT_PARTICIPATION
        const val P2P_DEFAULT_RELAY_PEER = P2PConfig.DEFAULT_RELAY_PEER_MODE
        const val P2P_DEFAULT_NAT = P2PConfig.DEFAULT_NAT_TRAVERSAL
        const val P2P_DEFAULT_STORE_FORWARD = P2PConfig.DEFAULT_STORE_AND_FORWARD

        private val KEY_PEER_CACHE = booleanPreferencesKey("peer_cache")
        private val KEY_PEER_EXCHANGE = booleanPreferencesKey("peer_exchange")
        private val KEY_DHT = booleanPreferencesKey("dht_participation")
        private val KEY_RELAY_PEER = booleanPreferencesKey("relay_peer")
        private val KEY_NAT = booleanPreferencesKey("nat_traversal")
        private val KEY_STORE_FORWARD = booleanPreferencesKey("store_and_forward")
        private val KEY_REMOVED_MULTI_NODE = booleanPreferencesKey("multi_node")
        private val KEY_REMOVED_REDUCE_RELAY = booleanPreferencesKey("reduce_default_relay")
    }
}
