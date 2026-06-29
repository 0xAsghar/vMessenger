package ir.vmessenger.network.dht

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.network.P2PConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery and network gating for embedded DHT participation (rc27).
 */
@Singleton
class EmbeddedDhtPolicy @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun shouldParticipate(): Boolean {
        if (!P2PConfig.dhtParticipationEnabled) return false
        if (isLowBattery()) return false
        return isOnWifi() || isCharging()
    }

    fun shouldAdvertise(host: String): Boolean =
        shouldParticipate() && host != "0.0.0.0" && host.isNotBlank()

    private fun isLowBattery(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..15 && !bm.isCharging
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
