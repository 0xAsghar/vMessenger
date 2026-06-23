package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun Identicon(
    seed: ByteArray,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val colors = remember(seed) { identiconColors(seed) }
    val pattern = remember(seed) { identiconPattern(seed) }
    Canvas(modifier = modifier.size(size)) {
        val cell = this.size.width / 5f
        for (row in 0 until 5) {
            for (col in 0 until 3) {
                val mirroredCol = if (col < 2) col else 4 - col
                if (pattern[row * 3 + col]) {
                    drawRect(
                        color = colors[row % colors.size],
                        topLeft = Offset(mirroredCol * cell, row * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
    }
}

private fun identiconPattern(seed: ByteArray): BooleanArray {
    val bits = BooleanArray(15)
    for (i in bits.indices) {
        bits[i] = (seed[i % seed.size].toInt() and (1 shl (i % 8))) != 0
    }
    return bits
}

private fun identiconColors(seed: ByteArray): List<Color> {
    val palette = listOf(
        Color(0xFF5C6BC0),
        Color(0xFF26A69A),
        Color(0xFFEF5350),
        Color(0xFFAB47BC),
        Color(0xFFFFA726),
    )
    val index = abs(seed.firstOrNull()?.toInt() ?: 0) % palette.size
    return listOf(palette[index], palette[(index + 2) % palette.size])
}
