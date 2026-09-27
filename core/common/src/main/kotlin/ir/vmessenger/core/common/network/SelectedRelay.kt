package ir.vmessenger.core.common.network

/**
 * The relay chosen to publish on and listen through: the healthiest one switched on in the node list.
 * There is no other kind since 2.2.2, which removed the built-in default relay.
 */
data class SelectedRelay(
    val url: String,
)
