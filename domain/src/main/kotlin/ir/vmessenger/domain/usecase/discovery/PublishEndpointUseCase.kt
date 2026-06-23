package ir.vmessenger.domain.usecase.discovery

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.domain.repository.DiscoveryRepository
import javax.inject.Inject

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
