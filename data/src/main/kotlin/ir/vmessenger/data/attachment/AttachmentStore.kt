package ir.vmessenger.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.database.entity.MessageContentType
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CopiedAttachment(
    val file: File,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentType: MessageContentType,
)

/**
 * App-private storage for chat attachments. Outgoing picks are copied under
 * files/attachments/out (content Uris are not durable), incoming transfers are
 * written under files/attachments/in.
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val outDir = File(context.filesDir, "attachments/out").apply { mkdirs() }
    private val inDir = File(context.filesDir, "attachments/in").apply { mkdirs() }

    fun copyFromUri(sourceUri: String): CopiedAttachment {
        val uri = Uri.parse(sourceUri)
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: DEFAULT_MIME
        val displayName = queryDisplayName(uri) ?: fallbackName(mimeType)
        val target = File(outDir, "${UUID.randomUUID()}-${sanitize(displayName)}")
        val size = resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("cannot open $sourceUri")
        if (size !in 1..MAX_ATTACHMENT_BYTES) {
            target.delete()
            error("attachment too large (${size / MEGABYTE} MB, max ${MAX_ATTACHMENT_BYTES / MEGABYTE} MB)")
        }
        return CopiedAttachment(
            file = target,
            fileName = displayName,
            mimeType = mimeType,
            sizeBytes = size,
            contentType = contentTypeFor(mimeType),
        )
    }

    fun newIncomingFile(fileName: String): File =
        File(inDir, "${UUID.randomUUID()}-${sanitize(fileName)}")

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }

    private fun fallbackName(mimeType: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
        return "attachment.$ext"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\u0600-\\u06FF-]"), "_").take(MAX_NAME_LENGTH)

    companion object {
        const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
        private const val MEGABYTE = 1024 * 1024
        private const val MAX_NAME_LENGTH = 80
        private const val DEFAULT_MIME = "application/octet-stream"

        fun contentTypeFor(mimeType: String): MessageContentType = when {
            mimeType.startsWith("image/") -> MessageContentType.IMAGE
            mimeType.startsWith("video/") -> MessageContentType.VIDEO
            else -> MessageContentType.FILE
        }
    }
}
