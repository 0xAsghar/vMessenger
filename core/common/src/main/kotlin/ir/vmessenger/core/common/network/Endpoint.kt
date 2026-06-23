package ir.vmessenger.core.common.network

data class Endpoint(
    val transport: TransportId,
    val address: String,
    val expiresAtUnixMs: Long? = null,
)
