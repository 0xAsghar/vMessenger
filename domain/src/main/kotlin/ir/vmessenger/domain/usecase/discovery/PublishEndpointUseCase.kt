package ir.vmessenger.domain.usecase.discovery

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.domain.repository.DiscoveryRepository
import javax.inject.Inject

class PublishNetworkEndpointsUseCase @Inject constructor(
    private val discoveryRepository: DiscoveryRepository,
) {
    suspend operator fun invoke(
        directHost: String? = null,
        directPort: Int? = null,
        relayUrl: String? = null,
    ): AppResult<Unit> {
        val endpoints = buildList {
            if (!directHost.isNullOrBlank() && directPort != null) {
                add(
                    Endpoint(
                        transport = TransportIds.INTERNET,
                        address = "$directHost:$directPort",
                    ),
                )
            }
            // No relay means none is enabled: the record names none rather than the built-in one.
            if (!relayUrl.isNullOrBlank()) {
                add(Endpoint(transport = TransportIds.RELAY, address = relayUrl))
            }
        }
        if (endpoints.isEmpty()) {
            return AppResult.Error(AppError.Validation("no endpoint to publish: no relay is enabled"))
        }
        return discoveryRepository.publishEndpoints(endpoints)
    }
}

class PublishEndpointUseCase @Inject constructor(
    private val discoveryRepository: DiscoveryRepository,
) {
    suspend operator fun invoke(host: String, port: Int): AppResult<Unit> =
        discoveryRepository.publishEndpoint(
            Endpoint(
                transport = TransportIds.INTERNET,
                address = "$host:$port",
            ),
        )
}

class JoinNetworkUseCase @Inject constructor(
    private val discoveryRepository: DiscoveryRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> = discoveryRepository.joinNetwork()
}
