package ir.vmessenger.node

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/** Header nginx sets to the real client address (see the templates under `deploy/nginx`). */
const val REAL_IP_HEADER = "X-Real-IP"

/**
 * The address rate limits and per-IP caps are keyed by.
 *
 * With [trustProxy] the node sits behind nginx on loopback, so every socket
 * peer is 127.0.0.1 and the only useful address is the one the proxy forwards
 * in `X-Real-IP`. Without it the header is ignored because a direct client
 * could set it to anything.
 */
fun ApplicationCall.clientIp(trustProxy: Boolean): String {
    if (trustProxy) {
        request.headers[REAL_IP_HEADER]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.origin.remoteAddress
}
