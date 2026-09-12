package ir.vmessenger.data.update

import ir.vmessenger.core.common.AppBuildInfo
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.update.CachedOffer
import ir.vmessenger.core.update.UpdateInstaller
import ir.vmessenger.core.update.UpdateState
import ir.vmessenger.core.update.UpdateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.security.MessageDigest

internal class FakeBuildInfo(
    override val isDebug: Boolean = true,
    override val versionName: String = "0.5.1",
    override val versionCode: Long = 45,
) : AppBuildInfo

internal class FakeUpdateStore(
    initial: UpdateState = UpdateState(),
    baseUrl: String? = null,
) : UpdateStore {
    private val stateFlow = MutableStateFlow(initial)
    private val baseUrlFlow = MutableStateFlow(baseUrl)

    val current: UpdateState get() = stateFlow.value

    override val state: Flow<UpdateState> = stateFlow
    override val debugBaseUrl: Flow<String?> = baseUrlFlow

    override suspend fun setLastCheck(unixMs: Long, offer: CachedOffer?) {
        stateFlow.value = stateFlow.value.copy(lastCheckedAtUnixMs = unixMs, offer = offer)
    }

    override suspend fun setSkippedVersion(versionName: String?) {
        stateFlow.value = stateFlow.value.copy(skippedVersion = versionName)
    }

    override suspend fun setDebugBaseUrl(url: String?) {
        baseUrlFlow.value = url
    }

    override suspend fun clear() {
        stateFlow.value = UpdateState()
    }
}

internal class FakeUpdateInstaller(
    var signerDigestHex: String?,
) : UpdateInstaller {
    var savedTo: String? = null

    override fun canInstallPackages(): Boolean = true

    override fun installUri(path: String): String? = "content://test$path"

    override fun installedSignerDigestHex(): String? = signerDigestHex

    override suspend fun copyTo(path: String, destinationUri: String): AppResult<Unit> {
        savedTo = destinationUri
        return AppResult.Success(Unit)
    }
}

/** A MockWebServer dispatcher that answers only the paths a test registered; everything else is a 404. */
internal class ReleaseRoutes : Dispatcher() {
    private val routes = mutableMapOf<String, () -> MockResponse>()

    /** The body is built per request, because a [Buffer] body is consumed when it is served. */
    fun on(path: String, response: () -> MockResponse) {
        routes[path] = response
    }

    override fun dispatch(request: RecordedRequest): MockResponse =
        routes[request.path?.substringBefore('?')]?.invoke() ?: MockResponse().setResponseCode(404)
}

internal fun textResponse(body: String): MockResponse = MockResponse().setBody(body)

internal fun bytesResponse(bytes: ByteArray): MockResponse = MockResponse()
    .setHeader("Content-Type", "application/vnd.android.package-archive")
    .setBody(Buffer().write(bytes))

/** `html_url` is deliberately included: the parser has to ignore fields it does not know. */
internal fun releaseJson(
    baseUrl: HttpUrl,
    tag: String,
    assets: List<Pair<String, Long>>,
    prerelease: Boolean,
    draft: Boolean,
): String {
    val entries = assets.joinToString(",") { (name, size) ->
        """{"name":"$name","browser_download_url":"${baseUrl}dl/$name","size":$size}"""
    }
    return """
        {"tag_name":"$tag","name":"vMessenger ${tag.removePrefix("v")}","body":"What's new",
        "published_at":"2026-01-15T10:00:00Z","prerelease":$prerelease,"draft":$draft,
        "html_url":"https://github.com/0xAsghar/vMessenger/releases/tag/$tag","assets":[$entries]}
    """.trimIndent()
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
