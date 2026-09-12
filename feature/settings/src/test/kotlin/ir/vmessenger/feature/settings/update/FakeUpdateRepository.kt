package ir.vmessenger.feature.settings.update

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.AvailableUpdate
import ir.vmessenger.domain.model.DownloadProgress
import ir.vmessenger.domain.model.UpdateAsset
import ir.vmessenger.domain.model.UpdateCheck
import ir.vmessenger.domain.model.UpdateStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** In-memory [ir.vmessenger.domain.repository.UpdateRepository]; every answer is a test knob. */
class FakeUpdateRepository : ir.vmessenger.domain.repository.UpdateRepository {
    var checkResult: AppResult<UpdateCheck> = AppResult.Success(UpdateCheck.UpToDate)
    var downloadEmissions: List<DownloadProgress> = emptyList()
    var canInstall: Boolean = true
    var installUriOrNull: String? = "content://updates/apk"
    var saveResult: AppResult<Unit> = AppResult.Success(Unit)

    val status = MutableStateFlow(UpdateStatus(null, null, null))
    var skipped: String? = null
        private set
    var savedTo: String? = null
        private set
    var forcedChecks: Int = 0
        private set

    override fun observeStatus(): Flow<UpdateStatus> = status

    override suspend fun check(force: Boolean): AppResult<UpdateCheck> {
        if (force) forcedChecks += 1
        return checkResult
    }

    override fun download(update: AvailableUpdate): Flow<DownloadProgress> = flowOf(*downloadEmissions.toTypedArray())

    override suspend fun skip(versionName: String) {
        skipped = versionName
    }

    override fun canInstallPackages(): Boolean = canInstall

    override fun installUri(path: String): String? = installUriOrNull

    override suspend fun saveTo(path: String, destinationUri: String): AppResult<Unit> {
        savedTo = destinationUri
        return saveResult
    }

    companion object {
        val UPDATE = AvailableUpdate(
            versionName = "1.1.0",
            releaseNotes = "notes",
            publishedAtUnixMs = 0L,
            asset = UpdateAsset("vMessenger-1.1.0-arm64-v8a.apk", "https://example.invalid/a.apk", 1_024L),
            checksumsUrl = "https://example.invalid/SHA256SUMS.txt",
            signingUrl = "https://example.invalid/SIGNING.txt",
        )

        val FAILURE = AppError.UpdateChecksumMismatch
    }
}
