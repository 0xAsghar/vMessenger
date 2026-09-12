package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.AvailableUpdate
import ir.vmessenger.domain.model.DownloadProgress
import ir.vmessenger.domain.model.UpdateCheck
import ir.vmessenger.domain.model.UpdateStatus
import kotlinx.coroutines.flow.Flow

/**
 * In-app updates from the project's public GitHub releases.
 *
 * Nothing here trusts the network: the downloaded APK must match the release's
 * published SHA-256, and its signer must match the certificate the running app
 * was installed with — otherwise a compromised release page (or anything between
 * it and the device) could hand the user a different app under the same name.
 */
interface UpdateRepository {
    fun observeStatus(): Flow<UpdateStatus>

    /**
     * Asks GitHub for the latest release.
     *
     * Throttled to one network call a day unless [force] is set, because the
     * unauthenticated API is rate-limited per IP and a background check must never be the
     * reason a manual one fails. Inside the window the **last outcome** is returned, not a
     * blanket "up to date": an update found yesterday is still an update today.
     */
    suspend fun check(force: Boolean): AppResult<UpdateCheck>

    /** Downloads and verifies [update]; the last emission carries the file to install. */
    fun download(update: AvailableUpdate): Flow<DownloadProgress>

    /** Stops offering this version until a newer one appears. */
    suspend fun skip(versionName: String)

    /**
     * True when this build can install an APK itself. False sends the user to
     * `ACTION_MANAGE_UNKNOWN_APP_SOURCES`; the answer is re-read on resume.
     */
    fun canInstallPackages(): Boolean

    /**
     * Content Uri for the verified APK, for `ACTION_VIEW`/the package installer.
     * Null when the file is gone.
     */
    fun installUri(path: String): String?

    /**
     * Copies the verified APK to a user-chosen document Uri. The way out when the
     * new APK is signed by a different key than the installed one: Android refuses
     * that upgrade, so the user keeps the file, uninstalls and installs by hand.
     */
    suspend fun saveTo(path: String, destinationUri: String): AppResult<Unit>
}
