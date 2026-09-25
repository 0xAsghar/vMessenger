package ir.vmessenger.core.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class SpkiPinTest {

    @Test
    fun `a pin is what openssl computes for the certificate's key`() {
        assertEquals(FIXED_PIN, SpkiPin.of(fixedCertificate()).text)
    }

    @Test
    fun `parses exactly 43 base64url characters`() {
        assertNotNull(SpkiPin.parse(FIXED_PIN))
        assertNull(SpkiPin.parse(FIXED_PIN.dropLast(1)))
        assertNull(SpkiPin.parse("$FIXED_PIN="))
        assertNull(SpkiPin.parse(FIXED_PIN.replace('-', '+')))
        assertNull(SpkiPin.parse(""))
        // 43 characters that decode to 32 bytes only when the final character's low bits are zero.
        assertEquals(FIXED_PIN, SpkiPin.parse(FIXED_PIN)?.text)
    }

    @Test
    fun `matches the certificate it was made from and no other`() {
        val pin = SpkiPin.parse(FIXED_PIN)!!
        assertTrue(pin.matches(fixedCertificate()))
        assertFalse(SpkiPin.parse("A".repeat(42) + "A")!!.matches(fixedCertificate()))
        assertEquals(pin, SpkiPin.of(fixedCertificate()))
        assertEquals(pin.hashCode(), SpkiPin.of(fixedCertificate()).hashCode())
    }

    companion object {
        /**
         * `openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 …`, and its pin from
         * `openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary
         *  | openssl base64 -A | tr '+/' '-_' | tr -d '='` — the command Deployment.md gives operators.
         */
        const val FIXED_PIN = "601FQOh6ckV1-Qbw-9F3cGprfojLs5_j4Hkn7DPKFfc"
        private const val FIXED_PEM = """-----BEGIN CERTIFICATE-----
MIIBlTCCATqgAwIBAgIUZCU2XI8zq4fgNXuDoe7oCM5xqhYwCgYIKoZIzj0EAwIw
FzEVMBMGA1UEAwwMMjAzLjAuMTEzLjEwMB4XDTI2MDkyNDE5NDgzNloXDTM2MDky
MTE5NDgzNlowFzEVMBMGA1UEAwwMMjAzLjAuMTEzLjEwMFkwEwYHKoZIzj0CAQYI
KoZIzj0DAQcDQgAEQX+V3Aulpc6lxdGM6TtpRkpHUUsQEfOHyU0rj34YBg047F3I
rddOmkhzYVHDt+h+dG2BVq7ILDrwkuwuAGLttqNkMGIwHQYDVR0OBBYEFCdK0vlI
cD2NN7hE0ZOIUISJ1VzDMB8GA1UdIwQYMBaAFCdK0vlIcD2NN7hE0ZOIUISJ1VzD
MA8GA1UdEwEB/wQFMAMBAf8wDwYDVR0RBAgwBocEywBxCjAKBggqhkjOPQQDAgNJ
ADBGAiEAxJumSDgC9ny+jlEoUIdSwRo6jO1xSdUn3fVjUrrYIbcCIQDm1cBei7p2
U6XgsXyAKk/bQW2VKUEtJJupQ90xDVU/7w==
-----END CERTIFICATE-----
"""

        fun fixedCertificate(): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(FIXED_PEM.byteInputStream()) as X509Certificate
    }
}
