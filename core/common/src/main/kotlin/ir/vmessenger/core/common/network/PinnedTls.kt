package ir.vmessenger.core.common.network

import okhttp3.OkHttpClient
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS for a pinned node URL ([NodeUrl.pins]): the server's certificate is accepted when its key
 * matches a pin, and for no other reason.
 *
 * The pin is the node's identity, so what a CA would check does not apply: the certificate is
 * self-signed, its name is whatever the installer wrote, and its dates are not what makes it the
 * right server. Hostname verification and validity are therefore not checked — the key is. An
 * unpinned URL never comes here and keeps the platform's CA validation.
 */
object PinnedTls {

    fun trustManager(pins: List<SpkiPin>): X509TrustManager {
        require(pins.isNotEmpty()) { "a pinned trust manager needs a pin" }
        return PinTrustManager(pins.toList())
    }

    /** [builder], set to trust exactly [pins]. */
    fun pinTo(builder: OkHttpClient.Builder, pins: List<SpkiPin>): OkHttpClient.Builder {
        val trustManager = trustManager(pins)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return builder
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
    }

    private class PinTrustManager(private val pins: List<SpkiPin>) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("the server sent no certificate")
            if (pins.none { it.matches(leaf) }) {
                throw CertificateException("the server's key is not the pinned one (${SpkiPin.of(leaf)})")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates are not trusted here")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
