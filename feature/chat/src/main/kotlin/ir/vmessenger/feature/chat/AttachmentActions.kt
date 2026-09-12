package ir.vmessenger.feature.chat

import android.graphics.Bitmap

/**
 * How a bubble reaches the encrypted attachment store (both go through the ViewModel):
 * [loadThumbnail] decodes a down-sampled preview by message id; [open] exports a
 * plaintext copy to the view cache and yields its path (null on failure).
 */
internal class AttachmentActions(
    val loadThumbnail: suspend (String) -> Bitmap?,
    val open: (String, (String?) -> Unit) -> Unit,
)
