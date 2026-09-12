package ir.vmessenger.core.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import androidx.core.graphics.withTranslation

private const val GRID_SIZE = 5
private const val GRID_HALF_COLUMNS = 3
private const val HEX_RADIX = 16
private const val HEX_PAIR = 2
private const val SECONDARY_ALPHA = 140
private const val OPAQUE_ALPHA = 255
private const val WASH_ALPHA = 40
private const val HAIRLINE_PX = 1f

/**
 * The rounded name chip above the pin. It is painted in the theme's surface colour so the label
 * stays readable over any basemap, light or dark.
 */
internal fun Canvas.drawLabelChip(rect: RectF, layout: StaticLayout, radius: Float, chrome: MarkerChrome) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = chrome.surface }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = chrome.outline
        style = Paint.Style.STROKE
        strokeWidth = HAIRLINE_PX
    }
    drawRoundRect(rect, radius, radius, fill)
    drawRoundRect(rect, radius, radius, stroke)
    withTranslation(
        rect.left + (rect.width() - layout.width) / 2f,
        rect.top + (rect.height() - layout.height) / 2f,
    ) {
        layout.draw(this)
    }
}

/**
 * The contact's identicon inside an opaque disc, using the very same 5x5 mirrored pattern as the
 * design system's `Avatar`, so a contact looks identical in a list and on the map.
 */
internal fun Canvas.drawAvatarDisc(
    bounds: RectF,
    seed: ByteArray,
    accent: Int,
    chrome: MarkerChrome,
    ringWidth: Float,
) {
    val centerX = bounds.centerX()
    val centerY = bounds.centerY()
    val radius = bounds.width() / 2f
    drawCircle(centerX, centerY, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = chrome.surface })
    val inner = radius - ringWidth
    val checkpoint = save()
    clipPath(Path().apply { addCircle(centerX, centerY, inner, Path.Direction.CW) })
    drawIdenticon(RectF(centerX - inner, centerY - inner, centerX + inner, centerY + inner), seed, accent)
    restoreToCount(checkpoint)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = chrome.outline
        style = Paint.Style.STROKE
        strokeWidth = HAIRLINE_PX
    }
    drawCircle(centerX, centerY, radius - HAIRLINE_PX / 2f, ring)
}

/** The little triangle that puts the pin's tip exactly on the coordinate. */
internal fun Canvas.drawPointer(rect: RectF, chrome: MarkerChrome) {
    val path = Path().apply {
        moveTo(rect.left, rect.top)
        lineTo(rect.right, rect.top)
        lineTo(rect.centerX(), rect.bottom)
        close()
    }
    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = chrome.surface })
}

private fun Canvas.drawIdenticon(bounds: RectF, seed: ByteArray, accent: Int) {
    drawRect(
        bounds,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            alpha = WASH_ALPHA
        },
    )
    val pattern = identiconPattern(seed)
    val cell = bounds.width() / GRID_SIZE
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (row in 0 until GRID_SIZE) {
        for (col in 0 until GRID_HALF_COLUMNS) {
            if (!pattern[row * GRID_HALF_COLUMNS + col]) continue
            paint.color = accent
            paint.alpha = if (row % 2 == 0) OPAQUE_ALPHA else SECONDARY_ALPHA
            drawCell(bounds, paint, col, row, cell)
            val mirrored = GRID_SIZE - 1 - col
            if (mirrored != col) drawCell(bounds, paint, mirrored, row, cell)
        }
    }
}

private fun Canvas.drawCell(bounds: RectF, paint: Paint, col: Int, row: Int, cell: Float) {
    val left = bounds.left + col * cell
    val top = bounds.top + row * cell
    drawRect(left, top, left + cell, top + cell, paint)
}

/** Same bit derivation as `core:designsystem`'s identicon, so both renderings agree. */
private fun identiconPattern(seed: ByteArray): BooleanArray {
    val bits = BooleanArray(GRID_SIZE * GRID_HALF_COLUMNS)
    if (seed.isEmpty()) return bits
    for (i in bits.indices) {
        bits[i] = (seed[i % seed.size].toInt() and (1 shl (i % 8))) != 0
    }
    return bits
}

/** Tolerant hex decode: a malformed seed yields an empty pattern (an initial is drawn), never a crash. */
internal fun String.hexToBytes(): ByteArray = runCatching {
    ByteArray(length / HEX_PAIR) { index ->
        substring(index * HEX_PAIR, index * HEX_PAIR + HEX_PAIR).toInt(HEX_RADIX).toByte()
    }
}.getOrDefault(ByteArray(0))
