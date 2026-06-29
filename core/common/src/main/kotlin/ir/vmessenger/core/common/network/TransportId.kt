package ir.vmessenger.core.common.network

@JvmInline
value class TransportId(val value: String)

object TransportIds {
    val INTERNET = TransportId("INTERNET")
    val UDP = TransportId("UDP")
    val RELAY = TransportId("RELAY")
}
