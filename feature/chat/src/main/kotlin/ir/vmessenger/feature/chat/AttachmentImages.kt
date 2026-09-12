package ir.vmessenger.feature.chat

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream

/** Coil model for an attachment: the message id, never a path. */
@Immutable
internal data class AttachmentImageKey(val messageId: String)

/**
 * Reads an image straight out of the encrypted attachment store.
 *
 * Attachments are encrypted at rest, so the plaintext must never reach the file system:
 * the decrypted stream is handed to Coil as an [ImageSource] and the request that uses
 * this fetcher disables the disk cache, leaving the decoded bitmap in memory only.
 */
internal class AttachmentFetcher(
    private val messageId: String,
    private val options: Options,
    private val openStream: suspend (String) -> InputStream?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val stream = openStream(messageId) ?: throw IOException("attachment of $messageId is unavailable")
        return SourceResult(
            source = ImageSource(source = stream.source().buffer(), context = options.context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val openStream: suspend (String) -> InputStream?) :
        Fetcher.Factory<AttachmentImageKey> {
        override fun create(data: AttachmentImageKey, options: Options, imageLoader: ImageLoader): Fetcher =
            AttachmentFetcher(data.messageId, options, openStream)
    }
}

/**
 * Builds the image requests used by bubbles and the full-screen viewer.
 *
 * The fetcher is attached per request rather than to a custom `ImageLoader`, so the app's
 * single shared loader (and its memory cache) is reused while the decrypted bytes of these
 * particular requests still never touch the disk cache.
 */
@Stable
internal class AttachmentImages(openStream: suspend (String) -> InputStream?) {

    private val factory = AttachmentFetcher.Factory(openStream)

    fun request(context: Context, messageId: String): ImageRequest = ImageRequest.Builder(context)
        .data(AttachmentImageKey(messageId))
        .fetcherFactory(factory)
        .memoryCacheKey("$MEMORY_KEY_PREFIX$messageId")
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()

    private companion object {
        const val MEMORY_KEY_PREFIX = "vm-attachment:"
    }
}
