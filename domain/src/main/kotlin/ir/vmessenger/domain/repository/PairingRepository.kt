package ir.vmessenger.domain.repository

interface PairingRepository {
    suspend fun createMyDescriptor(displayLabel: String = ""): ByteArray?
    fun encodeDescriptor(descriptorBytes: ByteArray): String
    fun decodeDescriptor(payload: String): ByteArray?
    fun verifyDescriptor(descriptorBytes: ByteArray): Boolean
}
