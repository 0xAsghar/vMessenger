package ir.vmessenger.feature.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.LinearProgressIndicator
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
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.AttachmentType
import ir.vmessenger.domain.model.ChatAttachment
import java.io.File

@Composable
internal fun AttachmentContent(
    messageId: String,
    attachment: ChatAttachment,
    progress: AttachmentProgress?,
    contentColor: Color,
    actions: AttachmentActions,
) {
    val context = LocalContext.current
    val mimeType = attachment.mimeType
    val open: () -> Unit = {
        actions.open(messageId) { path ->
            if (path != null) openAttachment(context, path, mimeType) else showOpenFailed(context)
        }
    }
    Column {
        when {
            attachment.type == AttachmentType.IMAGE && attachment.localPath != null ->
                AttachmentThumbnail(messageId = messageId, loadThumbnail = actions.loadThumbnail, onClick = open)
            else -> AttachmentFileRow(attachment = attachment, contentColor = contentColor, onClick = open)
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    messageId: String,
    loadThumbnail: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, messageId) {
        value = loadThumbnail(messageId)
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
                .clickable(onClick = onClick),
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

/** Hands the exported plaintext copy (cacheDir/attachments-view) to an external viewer. */
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
    }.onFailure { showOpenFailed(context) }
}

private fun showOpenFailed(context: Context) {
    Toast.makeText(
        context,
        context.getString(R.string.feature_chat_attachment_open_failed),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun formatSize(bytes: Long): String = when {
    bytes >= MEGABYTE -> "%.1f MB".format(bytes / MEGABYTE.toFloat())
    bytes >= KILOBYTE -> "%.0f KB".format(bytes / KILOBYTE.toFloat())
    else -> "$bytes B"
}

private const val THUMBNAIL_MAX_DP = 220
private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024
