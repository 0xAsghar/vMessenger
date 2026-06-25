package ir.vmessenger.core.common.network

object RelayProof {
    fun buildListenerProofTranscript(listenerId: ByteArray, ts: Long): ByteArray =
        "vmessenger-relay-listener".toByteArray(Charsets.UTF_8) +
            listenerId +
            ts.toString().toByteArray(Charsets.UTF_8)
}
