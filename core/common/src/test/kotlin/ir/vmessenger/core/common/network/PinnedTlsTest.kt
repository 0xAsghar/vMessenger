package ir.vmessenger.core.common.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** A real TLS server with a self-signed certificate, the way a node set up on a bare IP has one. */
class PinnedTlsTest {
    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate

    @Before
    fun start() {
        // Named for a host the client never uses, so a pass cannot come from hostname verification.
        certificate = HeldCertificate.Builder()
            .commonName("node.invalid")
            .addSubjectAlternativeName("node.invalid")
            .build()
        server = serve(certificate)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `the pinned key is trusted without a CA, a matching name or dates`() {
        val expired = HeldCertificate.Builder()
            .addSubjectAlternativeName("elsewhere.invalid")
            .validityInterval(0, 1_000)
            .build()
        val old = serve(expired)
        try {
            assertEquals(200, get(pinned(SpkiPin.of(expired.certificate)), old))
        } finally {
            old.shutdown()
        }
        assertEquals(200, get(pinned(SpkiPin.of(certificate.certificate)), server))
    }

    @Test
    fun `another key is refused`() {
        val other = HeldCertificate.Builder().build()
        assertHandshakeFails { get(pinned(SpkiPin.of(other.certificate)), server) }
    }

    @Test
    fun `any of several pins will do, for key rotation`() {
        val other = HeldCertificate.Builder().build()
        assertEquals(200, get(pinned(SpkiPin.of(other.certificate), SpkiPin.of(certificate.certificate)), server))
    }

    @Test
    fun `control - a stock client does not trust the test certificate`() {
        assertHandshakeFails { get(OkHttpClient(), server) }
    }

    @Test
    fun `an unpinned node URL keeps the platform's CA check`() {
        assertTrue(failureOf("wss://127.0.0.1:${server.port}/relay") is SSLException)
        val dht = runCatching {
            runBlocking { WebSocketFrameClient.sendBinary("wss://127.0.0.1:${server.port}/dht", byteArrayOf(1)) }
        }
        assertTrue("got ${dht.exceptionOrNull()}", dht.exceptionOrNull() is SSLException)
    }

    @Test
    fun `a socket that opens on a target backend makes it the host's sticky IP`() {
        RelayDns.clearStickyIps()
        server.enqueue(MockResponse().withWebSocketUpgrade(echo))
        val url = "wss://localhost:${server.port}/relay#pin-sha256=${SpkiPin.of(certificate.certificate).text}"
        val opened = java.util.concurrent.CompletableFuture<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }
        }
        val socket = WebSocketFrameClient.openWebSocket(url, targetIp = "127.0.0.1", listener)
        opened.get(10, TimeUnit.SECONDS)
        socket.close(1000, null)
        assertEquals("127.0.0.1", RelayDns.stickyIp("localhost"))
    }

    @Test
    fun `a DHT request goes to the pinned node, and the pin stays off the wire`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(echo))
        val url = "wss://127.0.0.1:${server.port}/dht#pin-sha256=${SpkiPin.of(certificate.certificate).text}"
        val reply = WebSocketFrameClient.sendBinary(url, byteArrayOf(1, 2, 3))
        assertTrue(reply.contentEquals(byteArrayOf(1, 2, 3)))
        // What was dialled is NodeUrl.dialUrl, which NodeUrlTest shows carries no fragment.
        assertEquals("/dht", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `a relay socket to a pinned node with the wrong pin fails`() {
        val other = HeldCertificate.Builder().build()
        val error = failureOf("wss://127.0.0.1:${server.port}/relay#pin-sha256=${SpkiPin.of(other.certificate).text}")
        assertTrue("expected a TLS failure, got $error", error is SSLException)
    }

    private fun failureOf(url: String): Throwable {
        val failure = java.util.concurrent.CompletableFuture<Throwable>()
        val listener = object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.complete(t)
            }
        }
        WebSocketFrameClient.openWebSocket(url, targetIp = null, listener)
        return failure.get(10, TimeUnit.SECONDS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an address that is not a node URL is not dialled`() {
        WebSocketFrameClient.openWebSocket("https://example.invalid/relay", null, object : WebSocketListener() {})
    }

    private val echo = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocket.send(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun serve(held: HeldCertificate): MockWebServer {
        val handshake = HandshakeCertificates.Builder().heldCertificate(held).build()
        return MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start()
        }
    }

    private fun pinned(vararg pins: SpkiPin): OkHttpClient =
        PinnedTls.pinTo(OkHttpClient.Builder(), pins.toList()).build()

    private fun get(client: OkHttpClient, target: MockWebServer): Int {
        target.enqueue(MockResponse().setBody("ok"))
        val url = "https://127.0.0.1:${target.port}/healthz"
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }

    private fun assertHandshakeFails(block: () -> Unit) {
        try {
            block()
            fail("the handshake should have failed")
        } catch (expected: SSLException) {
            // the server's key was not accepted
        }
    }
}
