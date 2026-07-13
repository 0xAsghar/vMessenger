package ir.vmessenger.feature.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ir.vmessenger.domain.model.AttachmentType
import ir.vmessenger.domain.model.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun AttachmentContent(
    attachment: ChatAttachment,
    contentColor: Color,
) {
    val context = LocalContext.current
    val path = attachment.localPath
    when {
        attachment.type == AttachmentType.IMAGE && path != null ->
            AttachmentThumbnail(path = path, mimeType = attachment.mimeType)
        else -> AttachmentFileRow(attachment = attachment, contentColor = contentColor) {
            path?.let { openAttachment(context, it, attachment.mimeType) }
        }
    }
}

@Composable
private fun AttachmentThumbnail(path: String, mimeType: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(path) }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .widthIn(max = THUMBNAIL_MAX_DP.dp)
                .heightIn(max = THUMBNAIL_MAX_DP.dp)
                .clickable { openAttachment(context, path, mimeType) },
        )
    } else {
        Box(
            modifier = Modifier
                .size(THUMBNAIL_MAX_DP.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun AttachmentFileRow(
    attachment: ChatAttachment,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when (attachment.type) {
                AttachmentType.VIDEO -> Icons.Outlined.Videocam
                AttachmentType.IMAGE -> Icons.Outlined.Image
                AttachmentType.FILE -> Icons.AutoMirrored.Outlined.InsertDriveFile
            },
            contentDescription = null,
            tint = contentColor,
        )
        Column {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatSize(attachment.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
    }
}

private fun decodeThumbnail(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    if (largest <= 0) return null
    var sample = 1
    while (largest / (sample * 2) >= THUMBNAIL_TARGET_PX) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

private fun openAttachment(context: Context, path: String, mimeType: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(path),
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.feature_chat_attachment_open_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= MEGABYTE -> "%.1f MB".format(bytes / MEGABYTE.toFloat())
    bytes >= KILOBYTE -> "%.0f KB".format(bytes / KILOBYTE.toFloat())
    else -> "$bytes B"
}

private const val THUMBNAIL_MAX_DP = 220
private const val THUMBNAIL_TARGET_PX = 640
private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024
