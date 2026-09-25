package ir.vmessenger.core.nodesetup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InstallResultTest {
    @Test
    fun `a pinned result whose pin is its certificate's key is accepted`() {
        val result = InstallResult.parse(pinnedJson(), "2.0.0")
        assertNotNull(result)
        assertEquals("ip-pinned", result!!.mode)
        assertEquals(PIN, result.pin)
        assertEquals("applied", result.keyOnlySsh)
    }

    @Test
    fun `a pin that is not the certificate's key, or URLs without it, are refused`() {
        assertNull(InstallResult.parse(pinnedJson(pin = "B".repeat(42) + "A"), "2.0.0"))
        assertNull(InstallResult.parse(pinnedJson(relay = "wss://203.0.113.10/relay"), "2.0.0"))
        assertNull(InstallResult.parse(pinnedJson(), "2.0.1"))
        assertNull(InstallResult.parse(pinnedJson().replace("\"ok\"", "\"failed\""), "2.0.0"))
        assertNull(InstallResult.parse("not json", "2.0.0"))
    }

    companion object {
        const val PIN = "601FQOh6ckV1-Qbw-9F3cGprfojLs5_j4Hkn7DPKFfc"
        const val CERT = "-----BEGIN CERTIFICATE-----\\n" +
            "MIIBlTCCATqgAwIBAgIUZCU2XI8zq4fgNXuDoe7oCM5xqhYwCgYIKoZIzj0EAwIw\\n" +
            "FzEVMBMGA1UEAwwMMjAzLjAuMTEzLjEwMB4XDTI2MDkyNDE5NDgzNloXDTM2MDky\\n" +
            "MTE5NDgzNlowFzEVMBMGA1UEAwwMMjAzLjAuMTEzLjEwMFkwEwYHKoZIzj0CAQYI\\n" +
            "KoZIzj0DAQcDQgAEQX+V3Aulpc6lxdGM6TtpRkpHUUsQEfOHyU0rj34YBg047F3I\\n" +
            "rddOmkhzYVHDt+h+dG2BVq7ILDrwkuwuAGLttqNkMGIwHQYDVR0OBBYEFCdK0vlI\\n" +
            "cD2NN7hE0ZOIUISJ1VzDMB8GA1UdIwQYMBaAFCdK0vlIcD2NN7hE0ZOIUISJ1VzD\\n" +
            "MA8GA1UdEwEB/wQFMAMBAf8wDwYDVR0RBAgwBocEywBxCjAKBggqhkjOPQQDAgNJ\\n" +
            "ADBGAiEAxJumSDgC9ny+jlEoUIdSwRo6jO1xSdUn3fVjUrrYIbcCIQDm1cBei7p2\\n" +
            "U6XgsXyAKk/bQW2VKUEtJJupQ90xDVU/7w==\\n-----END CERTIFICATE-----\\n"

        fun pinnedJson(
            pin: String = PIN,
            relay: String = "wss://203.0.113.10/relay#pin-sha256=$pin",
        ) = """{"schema": 1, "status": "ok", "exitStatus": 0, "code": null, "runId": "20260925-120000-abcd",
            "nodeVersion": "2.0.0", "nodeId": "c7fd", "mode": "ip-pinned", "tls": "selfsigned",
            "publicHost": "203.0.113.10", "publicPort": 443, "domain": null,
            "bootstrapUrl": "wss://203.0.113.10/dht#pin-sha256=$pin", "relayUrl": "$relay",
            "healthUrl": "https://203.0.113.10/healthz", "pin": "$pin", "certPem": "$CERT", "replacesUrls": [],
            "hardening": {"fail2ban": "on", "autoUpdates": "on", "timeSync": "on", "keyOnlySsh": "applied"},
            "warnings": ["OS_EOL"]}"""
    }
}
