package ir.vmessenger.node

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientIpTest {

    @Test
    fun `uses X-Real-IP only when the proxy is trusted`() = testApplication {
        application {
            routing {
                get("/trusted") { call.respondText(call.clientIp(trustProxy = true)) }
                get("/direct") { call.respondText(call.clientIp(trustProxy = false)) }
            }
        }

        assertEquals("203.0.113.9", client.get("/trusted") { header(REAL_IP_HEADER, " 203.0.113.9 ") }.bodyAsText())
        assertNotEquals("203.0.113.9", client.get("/direct") { header(REAL_IP_HEADER, "203.0.113.9") }.bodyAsText())

        val withoutHeader = client.get("/trusted").bodyAsText()
        assertTrue(withoutHeader.isNotEmpty())
        assertEquals(withoutHeader, client.get("/trusted") { header(REAL_IP_HEADER, "   ") }.bodyAsText())
    }
}
