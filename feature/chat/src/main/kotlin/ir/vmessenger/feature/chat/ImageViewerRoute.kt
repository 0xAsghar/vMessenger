package ir.vmessenger.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val DISMISS_DRAG_PX = 320f
private const val ZOOMED_EPSILON = 1.01f

/**
 * Full-bleed image viewer: pinch and double-tap to zoom, drag to pan, drag down to leave.
 *
 * The bitmap is fetched through Coil with [AttachmentImages], which reads the decrypted
 * stream straight from the attachment store — no plaintext copy is written for a photo,
 * unlike the export path video and other files take.
 */
@Composable
fun ImageViewerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImageViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val images = remember(viewModel) { AttachmentImages(viewModel::openAttachmentStream) }
    val request = remember(state.messageId, context) { images.request(context, state.messageId) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        ZoomableImage(model = request, onDismiss = onBack)
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.feature_chat_image_close),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ZoomableImage(model: Any?, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }

    if (failed) {
        Text(
            text = stringResource(R.string.feature_chat_image_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    AsyncImage(
        model = model,
        contentDescription = stringResource(R.string.feature_chat_image_viewer),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > ZOOMED_EPSILON) MIN_SCALE else DOUBLE_TAP_SCALE
                        offset = Offset.Zero
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    if (scale > ZOOMED_EPSILON) {
                        offset += pan
                        dragY = 0f
                    } else {
                        offset = Offset.Zero
                        dragY += pan.y
                        if (dragY > DISMISS_DRAG_PX) onDismiss()
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y + dragY
            },
        onError = { failed = true },
        contentScale = ContentScale.Fit,
    )
}
