package ir.vmessenger.core.location

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object LocationUpdateBus {
    private val _updates = MutableSharedFlow<LocationUpdate>(extraBufferCapacity = 32)
    val updates: SharedFlow<LocationUpdate> = _updates.asSharedFlow()

    private val _serviceRunning = MutableStateFlow(false)

    /**
     * True while [LocationService] holds the provider registration and feeds [updates].
     * [DeviceLocationProvider] watches this so the app never runs two location listeners.
     */
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    fun emit(update: LocationUpdate) {
        _updates.tryEmit(update)
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }
}
