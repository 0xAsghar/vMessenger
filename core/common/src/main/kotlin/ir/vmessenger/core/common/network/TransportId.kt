package ir.vmessenger.core.common.network

@JvmInline
value class TransportId(val value: String)

object TransportIds {
    val INTERNET = TransportId("INTERNET")
    val RELAY = TransportId("RELAY")
}
