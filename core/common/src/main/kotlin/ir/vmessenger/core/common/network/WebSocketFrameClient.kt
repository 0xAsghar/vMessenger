package ir.vmessenger.core.common.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Every WebSocket the app opens to a node goes through [openWebSocket], which reads the URL once
 * ([NodeUrl]) and picks the client for it:
 * - a pinned URL gets a client that trusts exactly its pins ([PinnedTls]); an unpinned one keeps
 *   the platform's CA validation;
 * - a relay dial can be held to one backend of a round-robin name (`targetIp`), and a socket that
 *   opens there makes that IP the host's sticky IP ([RelayDns]).
 *
 * Variants derive from one base client and share its connection pool and dispatcher. The
 * dispatcher is uncapped: an open WebSocket holds its call for its whole life, and the relay's
 * control channel, every circuit and the DHT's requests must not queue behind each other.
 */
object WebSocketFrameClient {
    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 30L
    private const val WRITE_TIMEOUT_S = 15L
    private const val PING_INTERVAL_S = 30L

    /** Pinned or backend-targeted clients kept for reuse; addresses come from the network, so bounded. */
    private const val MAX_CACHED_CLIENTS = 32

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = Int.MAX_VALUE
                    maxRequestsPerHost = Int.MAX_VALUE
                },
            )
            .dns(RelayDns.defaultDns)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()
    }

    private val clients = object : LinkedHashMap<String, OkHttpClient>(MAX_CACHED_CLIENTS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>): Boolean =
            size > MAX_CACHED_CLIENTS
    }

    /**
     * Opens a WebSocket to node [url]; the fragment (its pins) never goes on the wire. [targetIp]
     * holds the dial to one backend of the host, and once the socket opens that backend becomes the
     * host's sticky IP, which later relay sockets try first.
     *
     * @throws IllegalArgumentException when [url] is not a node URL ([NodeUrl.parse]).
     */
    fun openWebSocket(url: String, targetIp: String?, listener: WebSocketListener): WebSocket {
        val node = requireNotNull(NodeUrl.parse(url)) { "not a node URL" }
        val request = Request.Builder().url(node.dialUrl).build()
        // OkHttp gives WebSocket calls no EventListener, so the backend is recorded on open.
        val observed = if (targetIp == null) listener else StickOnOpen(node.host, targetIp, listener)
        return clientFor(node, targetIp).newWebSocket(request, observed)
    }

    /** One binary frame out, one back (the DHT's request/response over `/dht`). */
    suspend fun sendBinary(url: String, payload: ByteArray): ByteArray =
        suspendCancellableCoroutine { cont ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(payload.toByteString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    webSocket.close(1000, null)
                    if (cont.isActive) cont.resume(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("WebSocket closed without response: $reason"))
                    }
                }
            }
            val socket = openWebSocket(url, targetIp = null, listener)
            cont.invokeOnCancellation { socket.close(1000, "cancelled") }
        }

    /** The base client for an unpinned, untargeted socket; otherwise a cached variant. */
    private fun clientFor(node: NodeUrl, targetIp: String?): OkHttpClient {
        if (targetIp == null && !node.isPinned) return base
        val key = "${node.host}@${targetIp ?: "*"}|${node.pins.map { it.text }.sorted().joinToString(",")}"
        return synchronized(clients) {
            clients.getOrPut(key) {
                val builder = base.newBuilder()
                if (targetIp != null) builder.dns(RelayDns.dnsTargeting(node.host, targetIp))
                if (node.isPinned) PinnedTls.pinTo(builder, node.pins)
                builder.build()
            }
        }
    }

    private const val LOAD_FACTOR = 0.75f

    /** Makes [ip] the sticky IP of [host] when the socket opens, then hands everything to [delegate]. */
    private class StickOnOpen(
        private val host: String,
        private val ip: String,
        private val delegate: WebSocketListener,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            RelayDns.stick(host, ip)
            delegate.onOpen(webSocket, response)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = delegate.onMessage(webSocket, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = delegate.onMessage(webSocket, bytes)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
            delegate.onClosing(webSocket, code, reason)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            delegate.onClosed(webSocket, code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            delegate.onFailure(webSocket, t, response)
    }
}
