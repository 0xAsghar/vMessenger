package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

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
    val matrix = remember(payload) { encodeQrMatrix(payload) }
    Canvas(modifier = modifier.size(size)) {
        val moduleCount = matrix.width
        val moduleSize = this.size.width / moduleCount
        val dotSize = moduleSize * 0.82f
        val corner = CornerRadius(moduleSize * 0.28f, moduleSize * 0.28f)
        if (backgroundColor != Color.Transparent) {
            drawRect(color = backgroundColor)
        }
        for (x in 0 until moduleCount) {
            for (y in 0 until moduleCount) {
                if (matrix[x, y]) {
                    val inset = (moduleSize - dotSize) / 2f
                    drawRoundRect(
                        color = moduleColor,
                        topLeft = Offset(x * moduleSize + inset, y * moduleSize + inset),
                        size = Size(dotSize, dotSize),
                        cornerRadius = corner,
                    )
                }
            }
        }
    }
}

private fun encodeQrMatrix(payload: String, size: Int = 512): com.google.zxing.common.BitMatrix {
    return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
}
