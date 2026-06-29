package ir.vmessenger.core.common.network

/**
 * Result of selecting which relay URL to publish on and listen through.
 */
data class SelectedRelay(
    val url: String,
    val source: RelaySource,
)

enum class RelaySource {
    /** Health-ranked relay from the user's node list. */
    RANKED,
    /** Built-in default relay fallback. */
    DEFAULT,
}
