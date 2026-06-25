package ir.vmessenger.core.common.network

import kotlinx.coroutines.suspendCancellableCoroutine
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

object WebSocketFrameClient {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
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
            socketRef[0] = client.newWebSocket(request, listener)
            cont.invokeOnCancellation {
                socketRef[0]?.close(1000, "cancelled")
            }
        }

    fun httpClient(): OkHttpClient = client
}
