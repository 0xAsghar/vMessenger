package ir.vmessenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.p2pDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vmessenger_p2p",
)

data class P2PFlagSnapshot(
    val multiNodeEnabled: Boolean = true,
    val peerCacheEnabled: Boolean = true,
    val peerExchangeEnabled: Boolean = true,
    val dhtParticipationEnabled: Boolean = true,
    val relayPeerModeEnabled: Boolean = false,
    val natTraversalEnabled: Boolean = true,
    val storeAndForwardEnabled: Boolean = true,
    val reduceDefaultRelayEnabled: Boolean = true,
)

@Singleton
class P2PPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun load(): P2PFlagSnapshot {
        val preferences = context.p2pDataStore.data.first()
        return P2PFlagSnapshot(
            multiNodeEnabled = preferences[KEY_MULTI_NODE] ?: P2P_DEFAULT_MULTI_NODE,
            peerCacheEnabled = preferences[KEY_PEER_CACHE] ?: P2P_DEFAULT_PEER_CACHE,
            peerExchangeEnabled = preferences[KEY_PEER_EXCHANGE] ?: P2P_DEFAULT_PEER_EXCHANGE,
            dhtParticipationEnabled = preferences[KEY_DHT] ?: P2P_DEFAULT_DHT,
            relayPeerModeEnabled = preferences[KEY_RELAY_PEER] ?: P2P_DEFAULT_RELAY_PEER,
            natTraversalEnabled = preferences[KEY_NAT] ?: P2P_DEFAULT_NAT,
            storeAndForwardEnabled = preferences[KEY_STORE_FORWARD] ?: P2P_DEFAULT_STORE_FORWARD,
            reduceDefaultRelayEnabled = preferences[KEY_REDUCE_RELAY] ?: P2P_DEFAULT_REDUCE_RELAY,
        )
    }

    suspend fun save(snapshot: P2PFlagSnapshot) {
        context.p2pDataStore.edit { preferences ->
            preferences[KEY_MULTI_NODE] = snapshot.multiNodeEnabled
            preferences[KEY_PEER_CACHE] = snapshot.peerCacheEnabled
            preferences[KEY_PEER_EXCHANGE] = snapshot.peerExchangeEnabled
            preferences[KEY_DHT] = snapshot.dhtParticipationEnabled
            preferences[KEY_RELAY_PEER] = snapshot.relayPeerModeEnabled
            preferences[KEY_NAT] = snapshot.natTraversalEnabled
            preferences[KEY_STORE_FORWARD] = snapshot.storeAndForwardEnabled
            preferences[KEY_REDUCE_RELAY] = snapshot.reduceDefaultRelayEnabled
        }
    }

    suspend fun resetToDefaults() {
        save(P2PFlagSnapshot())
    }

    companion object {
        const val P2P_DEFAULT_MULTI_NODE = true
        const val P2P_DEFAULT_PEER_CACHE = true
        const val P2P_DEFAULT_PEER_EXCHANGE = true
        const val P2P_DEFAULT_DHT = true
        const val P2P_DEFAULT_RELAY_PEER = false
        const val P2P_DEFAULT_NAT = true
        const val P2P_DEFAULT_STORE_FORWARD = true
        const val P2P_DEFAULT_REDUCE_RELAY = true

        private val KEY_MULTI_NODE = booleanPreferencesKey("multi_node")
        private val KEY_PEER_CACHE = booleanPreferencesKey("peer_cache")
        private val KEY_PEER_EXCHANGE = booleanPreferencesKey("peer_exchange")
        private val KEY_DHT = booleanPreferencesKey("dht_participation")
        private val KEY_RELAY_PEER = booleanPreferencesKey("relay_peer")
        private val KEY_NAT = booleanPreferencesKey("nat_traversal")
        private val KEY_STORE_FORWARD = booleanPreferencesKey("store_and_forward")
        private val KEY_REDUCE_RELAY = booleanPreferencesKey("reduce_default_relay")
    }
}
