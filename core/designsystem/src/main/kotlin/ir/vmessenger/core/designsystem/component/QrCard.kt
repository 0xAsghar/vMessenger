package ir.vmessenger.core.designsystem.component

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun QrCard(
    payload: String,
    userHash: String,
    modifier: Modifier = Modifier,
    qrSize: Dp = 220.dp,
    showShareActions: Boolean = true,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StyledQrCode(
            payload = payload,
            modifier = Modifier.padding(vertical = 8.dp),
            size = qrSize,
        )
        UserHashLabel(modifier = Modifier.padding(top = 16.dp))
        UserHashText(
            text = userHash,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
        )
        if (showShareActions) {
            UserHashShareRow(userHash = userHash)
        }
    }
}

@Composable
fun StyledQrCode(
    payload: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    moduleColor: Color = MaterialTheme.colorScheme.onBackground,
    backgroundColor: Color = Color.Transparent,
) {
    val density = LocalDensity.current
    val pixelSize = with(density) { size.roundToPx().coerceAtLeast(1) }
    val bitmap = produceState<Bitmap?>(initialValue = null, payload, pixelSize, moduleColor, backgroundColor) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                rasterizeQr(payload, pixelSize, moduleColor, backgroundColor)
            }.getOrNull()
        }
    }.value

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

private fun rasterizeQr(
    payload: String,
    pixelSize: Int,
    moduleColor: Color,
    backgroundColor: Color,
): Bitmap {
    val matrix = encodeQrMatrix(payload)
    val moduleCount = matrix.width
    val bitmap = Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    if (backgroundColor == Color.Transparent) {
        canvas.drawColor(android.graphics.Color.TRANSPARENT)
    } else {
        canvas.drawColor(backgroundColor.toArgb())
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = moduleColor.toArgb()
        style = Paint.Style.FILL
    }
    val cellSize = pixelSize.toFloat() / moduleCount
    for (y in 0 until moduleCount) {
        for (x in 0 until moduleCount) {
            if (matrix[x, y]) {
                canvas.drawRect(
                    x * cellSize,
                    y * cellSize,
                    (x + 1) * cellSize,
                    (y + 1) * cellSize,
                    paint,
                )
            }
        }
    }
    return bitmap
}

private fun encodeQrMatrix(payload: String): com.google.zxing.common.BitMatrix {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
    )
    return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
}
