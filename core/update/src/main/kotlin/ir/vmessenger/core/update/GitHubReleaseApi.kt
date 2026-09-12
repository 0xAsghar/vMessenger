package ir.vmessenger.core.update

import ir.vmessenger.core.common.AppBuildInfo
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A GitHub release as `/releases/latest` reports it; unknown fields are ignored. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

/**
 * One OkHttp client for every updater request, so connections and the thread
 * pool are shared between the release lookup and the download.
 */
internal object UpdateHttp {
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 60L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Reads the project's public GitHub releases. Nothing here is authenticated:
 * the release page is public, and an unauthenticated client leaks no identity.
 */
@Singleton
class GitHubReleaseApi @Inject constructor(
    private val buildInfo: AppBuildInfo,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latestRelease(baseUrlOverride: String? = null): AppResult<GitHubRelease> {
        val url = "${resolveBaseUrl(baseUrlOverride)}/$LATEST_RELEASE_PATH"
        return when (val body = fetch(url, ACCEPT_JSON)) {
            is AppResult.Error -> body
            is AppResult.Success -> decode(body.data)
        }
    }

    /** `SHA256SUMS.txt` and `SIGNING.txt`; both are small enough to hold in memory. */
    suspend fun fetchText(url: String): AppResult<String> = fetch(url, ACCEPT_ANY)

    /**
     * The release API host. A debuggable build may be pointed at a local
     * `python3 -m http.server` to rehearse an update offline; a release build
     * ignores the override entirely, because the app forbids cleartext traffic
     * and must only ever trust github.com.
     */
    fun resolveBaseUrl(override: String?): String =
        override?.takeIf { buildInfo.isDebug && it.isNotBlank() }?.trimEnd('/') ?: DEFAULT_BASE_URL

    private suspend fun fetch(url: String, accept: String): AppResult<String> = withContext(Dispatchers.IO) {
        runCatching { UpdateHttp.client.newCall(request(url, accept)).execute().use(::readBody) }
            .getOrElse { AppResult.Error(AppError.Network(it.message ?: "release request failed")) }
    }

    private fun readBody(response: Response): AppResult<String> = when {
        response.isSuccessful -> AppResult.Success(response.body?.string().orEmpty())
        isRateLimited(response) -> AppResult.Error(AppError.UpdateRateLimited)
        else -> AppResult.Error(AppError.Network("release request failed: HTTP ${response.code}"))
    }

    /**
     * An exhausted unauthenticated quota comes back as 403/429 *with* the
     * rate-limit headers. A bare 403 is a different problem and must not be
     * reported to the user as "try again in an hour".
     */
    private fun isRateLimited(response: Response): Boolean =
        response.code in RATE_LIMIT_CODES &&
            (
                response.header("x-ratelimit-remaining") == "0" ||
                    response.header("x-ratelimit-reset") != null ||
                    response.header("retry-after") != null
                )

    private fun decode(body: String): AppResult<GitHubRelease> =
        runCatching { json.decodeFromString<GitHubRelease>(body) }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Error(AppError.Network("release JSON is unreadable: ${it.message}")) },
        )

    private fun request(url: String, accept: String): Request = Request.Builder()
        .url(url)
        .header("Accept", accept)
        .header("User-Agent", userAgent(buildInfo.versionName))
        .build()

    companion object {
        const val OWNER = "0xAsghar"
        const val REPO = "vMessenger"
        const val DEFAULT_BASE_URL = "https://api.github.com"
        const val LATEST_RELEASE_PATH = "repos/$OWNER/$REPO/releases/latest"

        /** Published next to the APKs; the `<asset>.apk.sha256` beside each one is the fallback. */
        const val CHECKSUMS_ASSET = "SHA256SUMS.txt"
        const val SIGNING_ASSET = "SIGNING.txt"
        const val PER_ASSET_CHECKSUM_SUFFIX = ".sha256"

        private const val ACCEPT_JSON = "application/vnd.github+json"
        private const val ACCEPT_ANY = "*/*"
        private val RATE_LIMIT_CODES = setOf(403, 429)

        /** GitHub answers a request without a User-Agent with 403, so every call carries one. */
        fun userAgent(versionName: String): String = "vMessenger/$versionName (+https://github.com/$OWNER/$REPO)"
    }
}
