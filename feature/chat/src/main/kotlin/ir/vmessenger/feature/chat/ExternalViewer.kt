package ir.vmessenger.feature.chat

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
private const val FALLBACK_MIME = "*/*"

/**
 * Hands the exported plaintext copy of an attachment to whatever app can display it.
 *
 * Only video and generic files take this path — images are decoded in-process so their
 * plaintext never reaches storage. Returns false when nothing on the device can open it,
 * which the caller turns into a snackbar.
 */
internal fun openExternally(context: Context, path: String, mimeType: String): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, File(path))
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.ifBlank { FALLBACK_MIME })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}.isSuccess

/**
 * Hands the exported plaintext copy of an attachment to another app to send on.
 *
 * The same FileProvider grant as [openExternally], and the same caveat: what leaves here is a
 * decrypted copy, so it is the user's choice of app that decides where it ends up. Returns false
 * when nothing on the device can take it.
 */
internal fun shareExternally(context: Context, path: String, mimeType: String): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, File(path))
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType.ifBlank { FALLBACK_MIME }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}.isSuccess
