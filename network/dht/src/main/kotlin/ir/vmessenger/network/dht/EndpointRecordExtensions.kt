package ir.vmessenger.network.dht

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.EndpointRecordTranscript
import ir.vmessenger.core.common.network.TransportId
import ir.vmessenger.core.proto.dht.v1.EndpointRecord

/** v2 signed bytes of this record (see [EndpointRecordTranscript.buildV2]); requires `transcript_version == 2`. */
fun EndpointRecord.buildTranscript(): ByteArray =
    EndpointRecordTranscript.buildV2(
        identityHash = identityHash.toByteArray(),
        identityPub = identityPub.toByteArray(),
        endpoints = endpointsList.map { EndpointRecordTranscript.Entry(it.transport, it.address) },
        publishedAtUnixMs = publishedAtUnixMs,
        ttlMs = ttlMs,
        sequence = sequence,
    )

fun EndpointRecord.toEndpoints(): List<Endpoint> = endpointsList.map {
    Endpoint(
        transport = TransportId(it.transport),
        address = it.address,
        expiresAtUnixMs = publishedAtUnixMs + ttlMs,
    )
}
