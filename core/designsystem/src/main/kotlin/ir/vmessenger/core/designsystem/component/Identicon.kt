package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

private const val GRID_SIZE = 5
private const val GRID_HALF_COLUMNS = 3

/**
 * The 5x5 vertically mirrored bit pattern behind every avatar. Internal on purpose: screens go
 * through [Avatar], which adds the shape, the background wash and the accessibility semantics.
 */
@Composable
internal fun Identicon(
    seed: ByteArray,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val pattern = remember(seed) { identiconPattern(seed) }
    Canvas(modifier = modifier) {
        val cell = size.width / GRID_SIZE
        for (row in 0 until GRID_SIZE) {
            for (col in 0 until GRID_HALF_COLUMNS) {
                if (!pattern[row * GRID_HALF_COLUMNS + col]) continue
                val color = colors[row % colors.size]
                drawCell(color, col, row, cell)
                val mirrored = GRID_SIZE - 1 - col
                if (mirrored != col) drawCell(color, mirrored, row, cell)
            }
        }
    }
}

private fun DrawScope.drawCell(color: Color, col: Int, row: Int, cell: Float) {
    drawRect(
        color = color,
        topLeft = Offset(col * cell, row * cell),
        size = Size(cell, cell),
    )
}

private fun identiconPattern(seed: ByteArray): BooleanArray {
    val bits = BooleanArray(GRID_SIZE * GRID_HALF_COLUMNS)
    if (seed.isEmpty()) return bits
    for (i in bits.indices) {
        bits[i] = (seed[i % seed.size].toInt() and (1 shl (i % 8))) != 0
    }
    return bits
}
