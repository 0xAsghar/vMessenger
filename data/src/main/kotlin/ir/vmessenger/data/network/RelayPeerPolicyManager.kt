package ir.vmessenger.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.RelayPeerPolicy
import ir.vmessenger.network.messaging.PeerRelayService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gates user-operated relay mode on explicit policy and device conditions (rc26).
 */
@Singleton
class RelayPeerPolicyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val peerRelayService: PeerRelayService,
) {
    @Volatile
    var policy: RelayPeerPolicy = RelayPeerPolicy.OFF
        set(value) {
            field = value
            peerRelayService.policy = value
            P2PConfig.relayPeerModeEnabled = value != RelayPeerPolicy.OFF
        }

    fun isRelayAllowedNow(): Boolean {
        if (!P2PConfig.relayPeerModeEnabled || policy == RelayPeerPolicy.OFF) return false
        return when (policy) {
            RelayPeerPolicy.OFF -> false
            RelayPeerPolicy.CONTACTS_ONLY -> true
            RelayPeerPolicy.WIFI_ONLY -> isOnWifi()
            RelayPeerPolicy.CHARGING_ONLY -> isCharging()
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.isCharging
    }
}
