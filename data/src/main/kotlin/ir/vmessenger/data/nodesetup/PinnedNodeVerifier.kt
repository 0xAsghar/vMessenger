package ir.vmessenger.data.nodesetup

import ir.vmessenger.core.common.network.NodeUrl
import ir.vmessenger.core.common.network.PinnedTls
import ir.vmessenger.core.nodesetup.InstallResult
import ir.vmessenger.core.nodesetup.Issue
import ir.vmessenger.core.nodesetup.IssueCode
import ir.vmessenger.core.nodesetup.NodeVerifier
import ir.vmessenger.core.nodesetup.Reachability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLException

/**
 * The phone's own look at a new node: `/healthz` over HTTPS from here, trusting exactly the node's
 * pin (or the CAs, for a domain with a CA certificate) — what the app's relay socket will do.
 * A server can be healthy from the inside and still unreachable from a phone: a provider firewall.
 */
class PinnedNodeVerifier @Inject constructor() : NodeVerifier {

    override suspend fun verify(result: InstallResult): Reachability = withContext(Dispatchers.IO) {
        val node = NodeUrl.parse(result.relayUrl)
            ?: return@withContext Reachability.Problem(Issue.of(IssueCode.RESULT_INVALID))
        val builder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        if (node.isPinned) PinnedTls.pinTo(builder, node.pins)
        val health = node.dialUrl.replaceFirst("wss://", "https://").substringBeforeLast('/') + "/healthz"
        try {
            builder.build().newCall(Request.Builder().url(health).build()).execute().use { response ->
                val ok = response.isSuccessful && response.body?.string()?.startsWith("ok") == true
                if (ok) Reachability.Ok else Reachability.Problem(Issue.of(IssueCode.REACH_REFUSED))
            }
        } catch (e: SSLException) {
            Reachability.Problem(Issue.of(IssueCode.REACH_TLS_MISMATCH, detail = e.message.orEmpty()))
        } catch (e: SocketTimeoutException) {
            Reachability.Problem(Issue.of(IssueCode.REACH_TIMEOUT, detail = e.message.orEmpty()))
        } catch (e: IOException) {
            Reachability.Problem(Issue.of(IssueCode.REACH_REFUSED, detail = e.message.orEmpty()))
        }
    }

    private companion object {
        const val TIMEOUT_S = 10L
    }
}
