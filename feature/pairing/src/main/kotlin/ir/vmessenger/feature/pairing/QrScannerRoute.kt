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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import java.util.concurrent.Executors

@Composable
fun QrScannerRoute(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: QrScanViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
        title = stringResource(R.string.scan_qr_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (hasCamera && uiState !is AddContactUiState.Success) {
                QrCameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    onQrScanned = viewModel::onQrScanned,
                )
                QrScanFrameOverlay()
            } else if (!hasCamera) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.camera_permission_grant))
                    }
                }
            }
            QrScannerStatusOverlay(uiState = uiState, onDone = onDone)
        }
    }
}

@Composable
private fun QrScanFrameOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(260.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)),
        ) {}
        Text(
            text = stringResource(R.string.scan_qr_hint),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun QrCameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onQrScanned: (String) -> Unit,
) {
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder().build().also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
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

@Composable
private fun QrScannerStatusOverlay(
    uiState: AddContactUiState,
    onDone: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is AddContactUiState.Error -> {
                Text(
                    text = uiState.message,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AddContactUiState.Success -> {
                Text(
                    text = stringResource(R.string.add_contact_success),
                    modifier = Modifier.align(Alignment.Center),
                )
                Button(
                    onClick = onDone,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                ) {
                    Text(stringResource(R.string.add_contact_done))
                }
            }
            else -> Unit
        }
    }
}
