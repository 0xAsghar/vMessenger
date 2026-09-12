package ir.vmessenger.domain.model

/**
 * A release newer than the running build, already narrowed to the one asset this
 * device can install.
 *
 * There is no store and no update server: releases are read from the project's
 * public GitHub releases, and everything that makes the download trustworthy —
 * the SHA-256 and the signer of the installed app — is checked on device.
 */
data class AvailableUpdate(
    /** Marketing version without the tag's `v`, e.g. `1.0.0`. */
    val versionName: String,
    val releaseNotes: String,
    val publishedAtUnixMs: Long,
    val asset: UpdateAsset,
    /** Where the release's `SHA256SUMS.txt` lives; the per-asset `.sha256` is the fallback. */
    val checksumsUrl: String?,
    /** Where the release's `SIGNING.txt` lives; absent on a release built without a keystore. */
    val signingUrl: String?,
)

data class UpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/** What a check concluded. [UpToDate] also covers "newer, but the user skipped it". */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck

    data class Available(val update: AvailableUpdate) : UpdateCheck
}

/** Streamed while an update downloads; [VerifiedFile] is the last emission of a good download. */
sealed interface DownloadProgress {
    data class Downloading(val bytesDone: Long, val totalBytes: Long) : DownloadProgress {
        val fraction: Float get() = if (totalBytes > 0) bytesDone.toFloat() / totalBytes else 0f
    }

    /** The bytes are in; the digest and the signer are being checked. */
    data object Verifying : DownloadProgress

    data class VerifiedFile(val path: String) : DownloadProgress

    data class Failed(val error: ir.vmessenger.core.common.AppError) : DownloadProgress
}

/**
 * What the updater knows between runs.
 *
 * [availableVersion] is what the last check concluded, persisted so the settings badge and
 * the home banner can be honest without a network call — and so an update found yesterday is
 * still offered today, which a throttle that simply answered "up to date" would have hidden.
 */
data class UpdateStatus(
    val lastCheckedAtUnixMs: Long?,
    val availableVersion: String?,
    /** A version the user chose not to be told about again. */
    val skippedVersion: String?,
) {
    /** True when there is something to tell the user about, the skip already accounted for. */
    val hasUpdate: Boolean get() = availableVersion != null
}
