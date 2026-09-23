package ir.vmessenger.feature.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Reusable QR/barcode scanner shell (camera permission, preview, scan frame).
 * Used for contact pairing and network-node import.
 */
@Composable
fun QrScannerScreen(
    title: String,
    hint: String,
    onNavigateBack: () -> Unit,
    scanPaused: Boolean = false,
    onQrScanned: (String) -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }

    DisposableEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    VMessengerScaffold(
        title = title,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (hasCamera && !scanPaused) {
                QrCameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    onQrScanned = onQrScanned,
                )
                QrScanFrameOverlay(hint = hint)
            } else if (!hasCamera) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(VmSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    VmText(
                        text = stringResource(R.string.camera_permission_required),
                        style = VmTheme.typography.bodyLg,
                        color = VmTheme.colors.textPrimary,
                        modifier = Modifier.padding(bottom = VmSpacing.lg),
                    )
                    VmButton(
                        text = stringResource(R.string.camera_permission_grant),
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    )
                }
            }
            overlay()
        }
    }
}

/**
 * Dimmed frame around the live preview: the scrim tells the eye where to aim, and the hint sits
 * on its own pill so it stays readable over whatever the camera happens to see.
 */
@Composable
private fun QrScanFrameOverlay(hint: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        VmSurface(
            modifier = Modifier.size(ViewfinderSize),
            shape = RoundedCornerShape(ViewfinderCorner),
            color = Color.Transparent,
            border = BorderStroke(ViewfinderBorder, Color.White),
        ) {}
        VmSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = HintBottomOffset),
            shape = RoundedCornerShape(ViewfinderCorner),
            color = Color.Black.copy(alpha = HINT_SCRIM_ALPHA),
        ) {
            VmText(
                text = hint,
                modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
                style = VmTheme.typography.bodyMd,
                color = Color.White,
            )
        }
    }
}

private val ViewfinderSize = 260.dp
private val ViewfinderCorner = 24.dp

/** Thick enough to stay visible against whatever the camera is pointed at. */
private val ViewfinderBorder = 3.dp

/** Clear of the viewfinder, so the hint never sits on top of the code being scanned. */
private val HintBottomOffset = 40.dp
private const val SCRIM_ALPHA = 0.35f
private const val HINT_SCRIM_ALPHA = 0.6f

@Composable
internal fun QrCameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onQrScanned: (String) -> Unit,
) {
    // Held across recompositions so both can be shut down when the view leaves: the analyzer used
    // to leak a thread per entry into the scanner, and the provider stayed bound to the lifecycle
    // with nothing on screen.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val providerHolder = remember { AtomicReference<ProcessCameraProvider?>(null) }
    DisposableEffect(analysisExecutor, providerHolder) {
        onDispose {
            runCatching { providerHolder.getAndSet(null)?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                providerHolder.set(cameraProvider)
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder().build().also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees,
                            )
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    barcodes.firstOrNull()?.rawValue?.let(onQrScanned)
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}
