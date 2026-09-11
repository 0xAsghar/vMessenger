package ir.vmessenger.node

import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Legacy raw-TCP DHT node for local emulator development (`--tcp` flag).
 */
class TcpDhtNodeServer(
    private val port: Int,
    private val handler: DhtRequestHandler,
) {
    private val log = LoggerFactory.getLogger(TcpDhtNodeServer::class.java)

    fun start() {
        log.info("tcp_dht_node_starting port={}", port)
        ServerSocket(port).use { server ->
            while (true) {
                val socket = server.accept()
                thread { handleClient(socket) }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val output = BufferedOutputStream(it.getOutputStream())
            try {
                val requestBytes = LengthPrefixedFrames.readFrame(input) ?: return
                val request = DhtRpcRequest.parseFrom(requestBytes)
                val response = handler.handle(request)
                LengthPrefixedFrames.writeFrame(output, response.toByteArray())
            } catch (e: IOException) {
                log.debug("tcp_dht_client_error reason=\"{}\"", e.message)
            }
        }
    }
}
