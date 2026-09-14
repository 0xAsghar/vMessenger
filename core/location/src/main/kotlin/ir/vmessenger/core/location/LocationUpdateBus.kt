package ir.vmessenger.core.location

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object LocationUpdateBus {
    private val _updates = MutableSharedFlow<LocationUpdate>(extraBufferCapacity = 32)
    val updates: SharedFlow<LocationUpdate> = _updates.asSharedFlow()

    private val _serviceRunning = MutableStateFlow(false)

    /**
     * True while [LocationService] holds the provider registration and feeds [updates].
     * [DeviceLocationProvider] watches this so the app never runs two location listeners.
     */
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _liveFixHolders = MutableStateFlow(0)

    /**
     * How many screens currently need a *live* fix rather than a last-known one.
     *
     * Collecting [DeviceLocationProvider] is not itself a reason to turn on GPS: showing a distance
     * to a contact is served perfectly well by the last known position. Without this counter the
     * provider registered whenever the foreground service stood down, so switching sharing off
     * moved the registration into the app process instead of ending it — and merely opening a tab
     * that displays distances kept it there.
     */
    val liveFixHolders: StateFlow<Int> = _liveFixHolders.asStateFlow()

    fun emit(update: LocationUpdate) {
        _updates.tryEmit(update)
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun acquireLiveFix() {
        _liveFixHolders.update { it + 1 }
    }

    fun releaseLiveFix() {
        _liveFixHolders.update { (it - 1).coerceAtLeast(0) }
    }
}
