package ir.vmessenger.network.dht

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportId
import ir.vmessenger.core.proto.dht.v1.EndpointRecord

fun EndpointRecord.buildTranscript(): ByteArray {
    val endpointsBytes = endpointsList
        .sortedBy { it.transport + it.address }
        .joinToString("\n") { "${it.transport}\t${it.address}" }
        .toByteArray(Charsets.UTF_8)
    return identityHash.toByteArray() +
        identityPub.toByteArray() +
        endpointsBytes +
        publishedAtUnixMs.toString().toByteArray(Charsets.UTF_8) +
        ttlMs.toString().toByteArray(Charsets.UTF_8) +
        sequence.toString().toByteArray(Charsets.UTF_8)
}

fun EndpointRecord.toEndpoints(): List<Endpoint> = endpointsList.map {
    Endpoint(
        transport = TransportId(it.transport),
        address = it.address,
        expiresAtUnixMs = publishedAtUnixMs + ttlMs,
    )
}
