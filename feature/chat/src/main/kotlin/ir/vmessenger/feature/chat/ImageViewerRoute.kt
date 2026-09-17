package ir.vmessenger.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/**
 * Full-bleed image viewer: pinch and double-tap to zoom, drag to pan, swipe up or down to leave.
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
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { ViewerDismissDistance.toPx() }
    val flingPx = with(LocalDensity.current) { ViewerDismissVelocity.toPx() }
    val gestures = remember(dismissPx, flingPx) { ViewerGestureState(dismissPx, flingPx) }

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
            .pointerInput(gestures) {
                detectTapGestures(onDoubleTap = { gestures.toggleZoom() })
            }
            .pointerInput(gestures) {
                detectZoomPanOrSwipe(
                    onTransform = { pan, zoom -> gestures.transform(pan, zoom) },
                    onRelease = { velocityY -> gestures.release(velocityY, scope, onDismiss) },
                )
            }
            .graphicsLayer {
                scaleX = gestures.scale
                scaleY = gestures.scale
                translationX = gestures.offset.x
                translationY = gestures.offset.y + gestures.dragY
            },
        onError = { failed = true },
        contentScale = ContentScale.Fit,
    )
}
