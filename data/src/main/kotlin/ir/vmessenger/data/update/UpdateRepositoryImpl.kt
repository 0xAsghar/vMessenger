package ir.vmessenger.data.update

import ir.vmessenger.core.common.AppBuildInfo
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.getOrNull
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.update.AssetSelector
import ir.vmessenger.core.update.CachedOffer
import ir.vmessenger.core.update.ChecksumFile
import ir.vmessenger.core.update.DownloadEvent
import ir.vmessenger.core.update.GitHubRelease
import ir.vmessenger.core.update.GitHubReleaseApi
import ir.vmessenger.core.update.ReleaseAsset
import ir.vmessenger.core.update.SemVer
import ir.vmessenger.core.update.SigningInfo
import ir.vmessenger.core.update.UpdateDownloader
import ir.vmessenger.core.update.UpdateInstaller
import ir.vmessenger.core.update.UpdateState
import ir.vmessenger.core.update.UpdateStore
import ir.vmessenger.domain.model.AvailableUpdate
import ir.vmessenger.domain.model.DownloadProgress
import ir.vmessenger.domain.model.UpdateAsset
import ir.vmessenger.domain.model.UpdateCheck
import ir.vmessenger.domain.model.UpdateStatus
import ir.vmessenger.domain.repository.UpdateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-app updater, over the project's public GitHub releases.
 *
 * Two things make a download trustworthy and both are checked here, on device:
 * the SHA-256 the release published for that exact asset, and the certificate
 * the release was signed with against the one this install carries. Neither is
 * optional — an update that cannot prove both is deleted, not offered.
 */
@Singleton
@Suppress("TooManyFunctions") // check, verify, download and the install hand-off of one flow
class UpdateRepositoryImpl @Inject constructor(
    private val api: GitHubReleaseApi,
    private val downloader: UpdateDownloader,
    private val store: UpdateStore,
    private val installer: UpdateInstaller,
    private val buildInfo: AppBuildInfo,
) : UpdateRepository {
    /** Injectable clock; the throttle is otherwise untestable. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** Read once at construction: `Build.SUPPORTED_ABIS` is not readable off-device. */
    internal var deviceAbis: List<String> = AssetSelector.deviceAbis()

    override fun observeStatus(): Flow<UpdateStatus> = store.state.map { state ->
        UpdateStatus(
            lastCheckedAtUnixMs = state.lastCheckedAtUnixMs,
            // Re-filtered on every read: the running build may have caught up with
            // the stored offer, or the user may have skipped it since it was found.
            availableVersion = state.offer?.versionName?.takeIf { worthOffering(it, state.skippedVersion) },
            skippedVersion = state.skippedVersion,
        )
    }

    override suspend fun check(force: Boolean): AppResult<UpdateCheck> {
        val state = store.state.first()
        val now = clock()
        val checkedRecently = state.lastCheckedAtUnixMs?.let { now - it < CHECK_INTERVAL_MS } == true
        // The unauthenticated API is rate-limited per IP, so a background check is
        // never allowed to be the reason the user's own "check now" fails.
        if (!force && checkedRecently) return AppResult.Success(lastOutcome(state))
        return fetchLatest(state, now)
    }

    override fun download(update: AvailableUpdate): Flow<DownloadProgress> = flow {
        val asset = update.asset
        downloader.download(asset.downloadUrl, asset.name, asset.sizeBytes).collect { event ->
            when (event) {
                is DownloadEvent.Progress -> emit(DownloadProgress.Downloading(event.bytesDone, event.totalBytes))
                is DownloadEvent.Failed -> emit(DownloadProgress.Failed(event.error))
                is DownloadEvent.Done -> {
                    emit(DownloadProgress.Verifying)
                    emit(verify(update, event.file, event.sha256Hex))
                }
            }
        }
    }

    override suspend fun skip(versionName: String) {
        store.setSkippedVersion(versionName)
    }

    override fun canInstallPackages(): Boolean = installer.canInstallPackages()

    override fun installUri(path: String): String? = installer.installUri(path)

    override suspend fun saveTo(path: String, destinationUri: String): AppResult<Unit> =
        installer.copyTo(path, destinationUri)

    /** What the last successful check concluded — an update found yesterday is still one today. */
    private fun lastOutcome(state: UpdateState): UpdateCheck {
        val offer = state.offer?.takeIf { worthOffering(it.versionName, state.skippedVersion) }
        return offer?.let { UpdateCheck.Available(it.toAvailableUpdate()) } ?: UpdateCheck.UpToDate
    }

    private suspend fun fetchLatest(state: UpdateState, now: Long): AppResult<UpdateCheck> {
        val baseUrl = store.debugBaseUrl.first()
        return when (val result = api.latestRelease(baseUrl)) {
            is AppResult.Error -> result
            is AppResult.Success -> record(result.data, state.skippedVersion, now)
        }
    }

    private suspend fun record(release: GitHubRelease, skippedVersion: String?, now: Long): AppResult<UpdateCheck> {
        val version = SemVer.parse(release.tagName)?.takeIf { worthOffering(release, it, skippedVersion) }
            ?: return remember(now, null)
        return when (val selected = selectAsset(release)) {
            is AppResult.Error -> selected
            is AppResult.Success -> remember(now, toOffer(release, version, selected.data))
        }
    }

    /**
     * Only a call that actually reached GitHub arms the throttle, and it stores its
     * own answer with it, so the next check inside the window replays this one.
     */
    private suspend fun remember(now: Long, offer: CachedOffer?): AppResult<UpdateCheck> {
        store.setLastCheck(now, offer)
        return AppResult.Success(offer?.let { UpdateCheck.Available(it.toAvailableUpdate()) } ?: UpdateCheck.UpToDate)
    }

    private fun selectAsset(release: GitHubRelease): AppResult<ReleaseAsset> =
        AssetSelector.select(release.assets.map { ReleaseAsset(it.name, it.browserDownloadUrl, it.size) }, deviceAbis)

    private fun worthOffering(release: GitHubRelease, version: SemVer, skippedVersion: String?): Boolean =
        // `/releases/latest` already hides drafts and prereleases, but the dev
        // base-URL override serves whatever JSON it is pointed at.
        !release.draft && !release.prerelease && worthOffering(version.toString(), skippedVersion)

    private fun worthOffering(versionName: String, skippedVersion: String?): Boolean =
        versionName != skippedVersion && SemVer.isNewerRelease(versionName, buildInfo.versionName)

    private fun toOffer(release: GitHubRelease, version: SemVer, asset: ReleaseAsset): CachedOffer = CachedOffer(
        versionName = version.toString(),
        releaseNotes = release.body.orEmpty(),
        publishedAtUnixMs = publishedAtUnixMs(release.publishedAt),
        assetName = asset.name,
        assetUrl = asset.downloadUrl,
        assetSizeBytes = asset.sizeBytes,
        checksumsUrl = release.assetUrl(GitHubReleaseApi.CHECKSUMS_ASSET),
        signingUrl = release.assetUrl(GitHubReleaseApi.SIGNING_ASSET),
    )

    private suspend fun verify(update: AvailableUpdate, file: File, sha256Hex: String): DownloadProgress {
        val expected = expectedDigest(update)
        // A release that published no checksum is refused, not trusted: there is
        // then nothing to compare the bytes against, and "the download finished"
        // is exactly what someone serving a different APK would also produce.
        if (expected == null || !expected.equals(sha256Hex, ignoreCase = true)) {
            file.delete()
            val reason = if (expected == null) "no sha256 was published" else "sha256 differs"
            AppLogger.warn(TAG, "discarded ${update.asset.name}: $reason")
            return DownloadProgress.Failed(AppError.UpdateChecksumMismatch)
        }
        return verifySigner(update, file)
    }

    private suspend fun expectedDigest(update: AvailableUpdate): String? {
        val fromSums = update.checksumsUrl
            ?.let { api.fetchText(it).getOrNull() }
            ?.let { ChecksumFile.digestFor(it, update.asset.name) }
        return fromSums ?: perAssetDigest(update)
    }

    /** `<asset>.apk.sha256` sits beside the APK, so its URL is the asset's plus the suffix. */
    private suspend fun perAssetDigest(update: AvailableUpdate): String? =
        api.fetchText(update.asset.downloadUrl + GitHubReleaseApi.PER_ASSET_CHECKSUM_SUFFIX)
            .getOrNull()
            ?.let { ChecksumFile.digestFor(it, update.asset.name) }

    private suspend fun verifySigner(update: AvailableUpdate, file: File): DownloadProgress {
        val released = update.signingUrl
            ?.let { api.fetchText(it).getOrNull() }
            ?.let { SigningInfo.digestFor(it, update.asset.name) }
        // A release signed by another key cannot upgrade this install anyway —
        // Android refuses it — and accepting it is the exact substitution the
        // checksum above is there to prevent. The user's way out is `saveTo`.
        if (!SigningInfo.matches(released, installer.installedSignerDigestHex())) {
            file.delete()
            AppLogger.warn(TAG, "discarded ${update.asset.name}: release signer differs from the installed one")
            return DownloadProgress.Failed(AppError.UpdateSignatureMismatch)
        }
        AppLogger.info(TAG, "verified ${update.asset.name}: sha256 and signer match")
        return DownloadProgress.VerifiedFile(file.absolutePath)
    }

    private fun GitHubRelease.assetUrl(name: String): String? =
        assets.firstOrNull { it.name == name }?.browserDownloadUrl

    private fun CachedOffer.toAvailableUpdate(): AvailableUpdate = AvailableUpdate(
        versionName = versionName,
        releaseNotes = releaseNotes,
        publishedAtUnixMs = publishedAtUnixMs,
        asset = UpdateAsset(name = assetName, downloadUrl = assetUrl, sizeBytes = assetSizeBytes),
        checksumsUrl = checksumsUrl,
        signingUrl = signingUrl,
    )

    /** GitHub stamps releases in ISO-8601 UTC; an unreadable one only costs the "published" line. */
    private fun publishedAtUnixMs(value: String?): Long =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        private const val TAG = "Updater"
    }
}
