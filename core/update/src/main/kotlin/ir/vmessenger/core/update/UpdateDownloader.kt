package ir.vmessenger.core.update

import ir.vmessenger.core.common.AppBuildInfo
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.update.di.UpdatesDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** What a running download reports; [Done] carries the digest computed over the streamed bytes. */
sealed interface DownloadEvent {
    data class Progress(val bytesDone: Long, val totalBytes: Long) : DownloadEvent

    data class Done(val file: File, val sha256Hex: String) : DownloadEvent

    data class Failed(val error: AppError) : DownloadEvent
}

/**
 * Streams a release asset into `cacheDir/updates`, hashing as it goes.
 *
 * The SHA-256 is accumulated from the same buffer that is written to disk: a
 * second pass would read back bytes that something else could have changed in
 * the meantime, and would double the IO of a 40 MB APK for nothing.
 *
 * The file lands as `<name>.part` and is renamed only once the body is fully
 * read, so a half-written APK can never be mistaken for a finished download —
 * and the directory is emptied first, because an update the user abandoned is
 * not worth keeping around in a world-readable cache.
 */
@Singleton
class UpdateDownloader @Inject constructor(
    @UpdatesDir private val directory: File,
    private val buildInfo: AppBuildInfo,
) {
    fun download(url: String, fileName: String, expectedSizeBytes: Long): Flow<DownloadEvent> = flow {
        directory.mkdirs()
        purge()
        val part = File(directory, fileName + PART_SUFFIX)
        val digest = try {
            stream(url, part, expectedSizeBytes)
        } catch (failure: IOException) {
            part.delete()
            emit(DownloadEvent.Failed(AppError.Network(failure.message ?: "the download failed")))
            return@flow
        }
        emit(finish(part, File(directory, fileName), digest))
    }.flowOn(Dispatchers.IO)

    /** Empties the updates directory — call it once the APK has been handed to the installer. */
    fun purge() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private suspend fun FlowCollector<DownloadEvent>.stream(
        url: String,
        target: File,
        expectedSizeBytes: Long,
    ): String = UpdateHttp.client.newCall(request(url)).execute().use { response ->
        val body = response.body
        if (!response.isSuccessful || body == null) throw IOException("HTTP ${response.code}")
        // Content-Length is absent on a chunked response; the release JSON's own
        // size is then the only number the progress bar can use.
        val total = body.contentLength().takeIf { it > 0 } ?: expectedSizeBytes
        copy(body.byteStream(), target, total)
    }

    private suspend fun FlowCollector<DownloadEvent>.copy(
        source: InputStream,
        target: File,
        totalBytes: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var done = 0L
        var reported = 0L
        target.outputStream().use { output ->
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
                done += read
                if (done - reported >= PROGRESS_STEP_BYTES) {
                    reported = done
                    emit(DownloadEvent.Progress(done, totalBytes))
                }
            }
        }
        emit(DownloadEvent.Progress(done, totalBytes))
        return digest.digest().toHexLowercase()
    }

    private fun finish(part: File, target: File, sha256Hex: String): DownloadEvent {
        target.delete()
        return if (part.renameTo(target)) {
            DownloadEvent.Done(target, sha256Hex)
        } else {
            part.delete()
            DownloadEvent.Failed(AppError.Network("could not move ${part.name} into place"))
        }
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", GitHubReleaseApi.userAgent(buildInfo.versionName))
        .build()

    companion object {
        const val PART_SUFFIX = ".part"

        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}
