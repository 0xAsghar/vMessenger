package ir.vmessenger.domain.model

data class DiscoveryStatus(
    val bootstrapped: Boolean,
    val knownNodes: Int,
    val publishedEndpoint: String?,
    val lastError: String?,
)
