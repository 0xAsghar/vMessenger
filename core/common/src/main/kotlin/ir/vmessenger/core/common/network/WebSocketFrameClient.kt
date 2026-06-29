package ir.vmessenger.core.common.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WebSocketFrameClient {
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    fun httpClient(): OkHttpClient = clientFor(DnsKey.DEFAULT)

    fun httpClientForRelayTarget(host: String, ip: String): OkHttpClient =
        clientFor(DnsKey.forTarget(host, ip), RelayDns.dnsTargeting(host, ip))

    fun httpClientWithPinning(host: String, targetIp: String? = null): OkHttpClient {
        val key = if (targetIp != null) DnsKey.forTarget(host, targetIp) else DnsKey.pinning(host)
        val dns = targetIp?.let { RelayDns.dnsTargeting(host, it) } ?: RelayDns.defaultDns
        return clients.getOrPut(key.id) {
            OkHttpClient.Builder()
                .dns(dns)
                .eventListener(PinningEventListener(host))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }
    }

    suspend fun sendBinary(url: String, payload: ByteArray): ByteArray =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(url).build()
            val socketRef = arrayOfNulls<WebSocket>(1)
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(payload.toByteString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    webSocket.close(1000, null)
                    if (cont.isActive) {
                        cont.resume(bytes.toByteArray())
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) {
                        cont.resumeWithException(t)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("WebSocket closed without response: $reason"))
                    }
                }
            }
            socketRef[0] = httpClient().newWebSocket(request, listener)
            cont.invokeOnCancellation {
                socketRef[0]?.close(1000, "cancelled")
            }
        }

    private data class DnsKey(val id: String) {
        companion object {
            val DEFAULT = DnsKey("default")
            fun forTarget(host: String, ip: String) = DnsKey("$host@$ip")
            fun pinning(host: String) = DnsKey("pin:$host")
        }
    }

    private fun clientFor(key: DnsKey, dns: Dns = RelayDns.defaultDns): OkHttpClient =
        clients.getOrPut(key.id) {
            OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }

    private class PinningEventListener(
        private val host: String,
    ) : EventListener() {
        override fun connectEnd(
            call: okhttp3.Call,
            inetSocketAddress: InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: okhttp3.Protocol?,
        ) {
            inetSocketAddress.address?.hostAddress?.let { RelayDns.pin(host, it) }
        }
    }
}
