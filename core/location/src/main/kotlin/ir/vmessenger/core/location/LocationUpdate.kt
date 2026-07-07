package ir.vmessenger.core.location

data class LocationUpdate(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val sampledAtUnixMs: Long,
)
