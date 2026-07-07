package ir.vmessenger.core.location

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object LocationUpdateBus {
    private val _updates = MutableSharedFlow<LocationUpdate>(extraBufferCapacity = 32)
    val updates: SharedFlow<LocationUpdate> = _updates.asSharedFlow()

    fun emit(update: LocationUpdate) {
        _updates.tryEmit(update)
    }
}
