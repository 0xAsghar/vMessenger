package ir.vmessenger.data.update

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.update.GitHubReleaseApi
import ir.vmessenger.core.update.UpdateDownloader
import ir.vmessenger.domain.model.AvailableUpdate
import ir.vmessenger.domain.model.DownloadProgress
import ir.vmessenger.domain.model.UpdateCheck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Drives the whole updater against a local server that serves a real release:
 * the JSON, the APK bytes, `SHA256SUMS.txt` and `SIGNING.txt`.
 */
@Suppress("TooManyFunctions") // one test per behaviour of one repository, plus the release fixture
class UpdateRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var routes: ReleaseRoutes
    private lateinit var store: FakeUpdateStore
    private lateinit var updatesDir: File

    private val installer = FakeUpdateInstaller(SIGNER_DIGEST)
    private val apk = ByteArray(APK_BYTES) { (it % 251).toByte() }
    private val apkDigest by lazy { sha256Hex(apk) }

    @Before
    fun setUp() {
        routes = ReleaseRoutes()
        server = MockWebServer()
        server.dispatcher = routes
        server.start()
        store = FakeUpdateStore(baseUrl = server.url("/").toString())
        updatesDir = Files.createTempDirectory("vmessenger-updates").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        updatesDir.deleteRecursively()
    }

    @Test
    fun theLatestReleaseIsOffered() = runBlocking {
        publish()
        val repository = repository()

        val update = requireAvailable(repository.check(force = true))

        assertEquals("1.0.0", update.versionName)
        assertEquals("vMessenger-1.0.0-arm64-v8a.apk", update.asset.name)
        assertEquals(apk.size.toLong(), update.asset.sizeBytes)
        assertEquals("What's new", update.releaseNotes)
        assertEquals(Instant.parse("2026-01-15T10:00:00Z").toEpochMilli(), update.publishedAtUnixMs)
        assertTrue(update.checksumsUrl.orEmpty().endsWith("/dl/SHA256SUMS.txt"))
        assertTrue(update.signingUrl.orEmpty().endsWith("/dl/SIGNING.txt"))
        assertEquals(NOW, store.current.lastCheckedAtUnixMs)

        val status = repository.observeStatus().first()
        assertEquals("1.0.0", status.availableVersion)
        assertEquals(NOW, status.lastCheckedAtUnixMs)
        assertTrue(status.hasUpdate)
    }

    @Test
    fun aPrereleaseIsNeverOffered() = runBlocking {
        publish(tag = "v1.1.0-rc1", prerelease = true)
        assertEquals(AppResult.Success(UpdateCheck.UpToDate), repository().check(force = true))

        // Also refused when the release was not flagged as one: the tag decides.
        publish(tag = "v1.1.0-rc1")
        assertEquals(AppResult.Success(UpdateCheck.UpToDate), repository().check(force = true))
    }

    @Test
    fun aDraftIsNeverOffered() = runBlocking {
        publish(draft = true)
        assertEquals(AppResult.Success(UpdateCheck.UpToDate), repository().check(force = true))
    }

    @Test
    fun theRunningVersionAndOlderOnesAreUpToDate() = runBlocking {
        publish(tag = "v0.5.1")
        assertEquals(AppResult.Success(UpdateCheck.UpToDate), repository().check(force = true))

        publish(tag = "v0.4.9")
        assertEquals(AppResult.Success(UpdateCheck.UpToDate), repository().check(force = true))
    }

    @Test
    fun aSkippedVersionReturnsOnlyWhenSomethingNewerShips() = runBlocking {
        publish(tag = "v1.0.0")
        val repository = repository()
        assertEquals("1.0.0", requireAvailable(repository.check(force = true)).versionName)

        repository.skip("1.0.0")
        // The badge goes quiet at once, although the stored offer is still 1.0.0.
        assertEquals("1.0.0", store.current.offer?.versionName)
        assertEquals(null, repository.observeStatus().first().availableVersion)
        assertEquals("1.0.0", repository.observeStatus().first().skippedVersion)
        assertEquals(AppResult.Success(UpdateCheck.UpToDate), repository.check(force = true))

        publish(tag = "v1.1.0")
        assertEquals("1.1.0", requireAvailable(repository.check(force = true)).versionName)
    }

    @Test
    fun anExhaustedRateLimitIsReportedAsSuch() = runBlocking {
        routes.on(LATEST_PATH) {
            MockResponse()
                .setResponseCode(403)
                .setHeader("x-ratelimit-remaining", "0")
                .setBody("""{"message":"API rate limit exceeded"}""")
        }

        assertEquals(AppResult.Error(AppError.UpdateRateLimited), repository().check(force = true))
        // A plain 403 is a different problem and must not be reported as a rate limit.
        routes.on(LATEST_PATH) { MockResponse().setResponseCode(403) }
        assertTrue(repository().check(force = true) is AppResult.Error)
    }

    @Test
    fun aCheckWithinTwentyFourHoursSkipsTheNetworkUnlessForced() = runBlocking {
        publish()
        store.setLastCheck(NOW - UpdateRepositoryImpl.CHECK_INTERVAL_MS + 1, null)
        val repository = repository()

        assertEquals(AppResult.Success(UpdateCheck.UpToDate), repository.check(force = false))
        assertEquals(0, server.requestCount)

        assertEquals("1.0.0", requireAvailable(repository.check(force = true)).versionName)
        assertEquals(1, server.requestCount)

        // Inside the window the stored outcome answers: an update found a moment
        // ago is still an update, and must not be hidden by the throttle.
        assertEquals("1.0.0", requireAvailable(repository.check(force = false)).versionName)
        assertEquals(1, server.requestCount)

        // Once the last check has aged out, the automatic path calls again.
        store.setLastCheck(NOW - UpdateRepositoryImpl.CHECK_INTERVAL_MS, store.current.offer)
        assertEquals("1.0.0", requireAvailable(repository.check(force = false)).versionName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theDebugBaseUrlOverrideIsIgnoredInAReleaseBuild() {
        val debug = GitHubReleaseApi(FakeBuildInfo(isDebug = true))
        val release = GitHubReleaseApi(FakeBuildInfo(isDebug = false))

        assertEquals("http://127.0.0.1:8000", debug.resolveBaseUrl("http://127.0.0.1:8000/"))
        assertEquals(GitHubReleaseApi.DEFAULT_BASE_URL, debug.resolveBaseUrl(null))
        assertEquals(GitHubReleaseApi.DEFAULT_BASE_URL, debug.resolveBaseUrl("   "))
        assertEquals(GitHubReleaseApi.DEFAULT_BASE_URL, release.resolveBaseUrl("http://127.0.0.1:8000/"))
    }

    @Test
    fun aVerifiedDownloadKeepsTheFile() = runBlocking {
        publish()
        val repository = repository()
        val update = requireAvailable(repository.check(force = true))

        val events = repository.download(update).toList()

        val verified = events.last() as DownloadProgress.VerifiedFile
        assertTrue(events.any { it is DownloadProgress.Downloading })
        assertTrue(events.contains(DownloadProgress.Verifying))
        assertEquals(update.asset.name, File(verified.path).name)
        assertEquals(apk.size.toLong(), File(verified.path).length())
        assertEquals(listOf(update.asset.name), updatesDir.list()?.sorted())
    }

    @Test
    fun aChecksumMismatchDeletesTheFile() = runBlocking {
        publish(publishedDigest = "0".repeat(64))
        val repository = repository()
        val update = requireAvailable(repository.check(force = true))

        val events = repository.download(update).toList()

        assertEquals(DownloadProgress.Failed(AppError.UpdateChecksumMismatch), events.last())
        assertEquals(emptyList<String>(), updatesDir.list()?.toList())
    }

    @Test
    fun aReleaseWithNoPublishedChecksumIsRefused() = runBlocking {
        publish(sidecars = setOf(GitHubReleaseApi.SIGNING_ASSET))
        val repository = repository()
        val update = requireAvailable(repository.check(force = true))

        val events = repository.download(update).toList()

        assertEquals(DownloadProgress.Failed(AppError.UpdateChecksumMismatch), events.last())
        assertEquals(emptyList<String>(), updatesDir.list()?.toList())
    }

    @Test
    fun thePerAssetChecksumIsTheFallbackForAMissingSha256sums() = runBlocking {
        publish(sidecars = setOf(PER_ASSET, GitHubReleaseApi.SIGNING_ASSET))
        val repository = repository()
        val update = requireAvailable(repository.check(force = true))

        assertEquals(null, update.checksumsUrl)
        assertTrue(repository.download(update).toList().last() is DownloadProgress.VerifiedFile)
    }

    @Test
    fun aReleaseSignedByAnotherKeyIsRefused() = runBlocking {
        publish()
        installer.signerDigestHex = "9".repeat(64)
        val repository = repository()
        val update = requireAvailable(repository.check(force = true))

        val events = repository.download(update).toList()

        assertEquals(DownloadProgress.Failed(AppError.UpdateSignatureMismatch), events.last())
        assertEquals(emptyList<String>(), updatesDir.list()?.toList())
    }

    private fun repository(buildInfo: FakeBuildInfo = FakeBuildInfo()): UpdateRepositoryImpl =
        UpdateRepositoryImpl(
            api = GitHubReleaseApi(buildInfo),
            downloader = UpdateDownloader(updatesDir, buildInfo),
            store = store,
            installer = installer,
            buildInfo = buildInfo,
        ).apply {
            deviceAbis = listOf("arm64-v8a", "armeabi-v7a")
            clock = { NOW }
        }

    /** Registers a whole release: the JSON plus whichever of its files [sidecars] asks for. */
    private fun publish(
        tag: String = "v1.0.0",
        prerelease: Boolean = false,
        draft: Boolean = false,
        sidecars: Set<String> = ALL_SIDECARS,
        publishedDigest: String = apkDigest,
    ) {
        val version = tag.removePrefix("v")
        val abiApk = "vMessenger-$version-arm64-v8a.apk"
        val universalApk = "vMessenger-$version-universal.apk"
        val assets = listOf(abiApk to apk.size.toLong(), universalApk to apk.size.toLong()) +
            sidecars.filterNot { it == PER_ASSET }.map { it to 0L }

        routes.on(LATEST_PATH) { textResponse(releaseJson(server.url("/"), tag, assets, prerelease, draft)) }
        routes.on("/dl/$abiApk") { bytesResponse(apk) }
        if (GitHubReleaseApi.CHECKSUMS_ASSET in sidecars) {
            routes.on("/dl/${GitHubReleaseApi.CHECKSUMS_ASSET}") {
                textResponse("$publishedDigest  $abiApk\r\n$apkDigest  $universalApk\r\n")
            }
        }
        if (PER_ASSET in sidecars) {
            routes.on("/dl/$abiApk${GitHubReleaseApi.PER_ASSET_CHECKSUM_SUFFIX}") {
                textResponse("$publishedDigest  $abiApk\n")
            }
        }
        if (GitHubReleaseApi.SIGNING_ASSET in sidecars) {
            routes.on("/dl/${GitHubReleaseApi.SIGNING_ASSET}") { textResponse(signingTxt(abiApk)) }
        }
    }

    private fun signingTxt(assetName: String): String = """
        == $assetName
        Signer #1 certificate DN: CN=vMessenger, O=vMessenger
        Signer #1 certificate SHA-256 digest: $SIGNER_DIGEST
    """.trimIndent()

    private fun requireAvailable(result: AppResult<UpdateCheck>): AvailableUpdate =
        ((result as AppResult.Success).data as UpdateCheck.Available).update

    private companion object {
        const val LATEST_PATH = "/${GitHubReleaseApi.LATEST_RELEASE_PATH}"
        const val PER_ASSET = "<per-asset>"
        const val SIGNER_DIGEST = "1f2e3d4c5b6a79880f1e2d3c4b5a69780e1d2c3b4a5968770d1c2b3a49586766"
        const val NOW = 1_760_000_000_000L

        /** Large enough that the 256 KiB progress step fires more than once. */
        const val APK_BYTES = 700 * 1024

        val ALL_SIDECARS = setOf(
            GitHubReleaseApi.CHECKSUMS_ASSET,
            GitHubReleaseApi.SIGNING_ASSET,
            PER_ASSET,
        )
    }
}
