package ir.vmessenger.core.designsystem.component

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.graphics.Canvas as AndroidCanvas

private const val QUIET_ZONE_MODULES = 1
private const val FINDER_SPAN = 7
private const val EYE_PUPIL_OFFSET = 2
private const val EYE_PUPIL_SPAN = 3

/**
 * One module of corner radius on the ring, no more. A scanner locates a code by the 1:1:3:1:1 run
 * through each corner pattern, and it checks that run diagonally as well as straight; rounding the
 * ring by two modules eats enough of the diagonal to lose the pattern at 6 pixels per module.
 */
private const val EYE_CORNER_MODULES = 1f

/**
 * 18% of the side: the cleared block covers ~3% of the code area and, at the versions a pairing
 * payload reaches (15-17), sits between the centre and the alignment patterns without touching one.
 */
private const val LOGO_SIDE_FRACTION = 0.18f
private const val LOGO_INSET_MODULES = 1

/** A painted code: the bitmap, its exact side in pixels, and the logo side as a fraction of that. */
internal data class QrRender(
    val bitmap: Bitmap,
    val sidePx: Int,
    val logoSideFraction: Float,
)

/** Paint-pass measurements in matrix modules, quiet zone included, so x/y map straight to the grid. */
private data class QrGeometry(
    val grid: Int,
    val cell: Float,
    val corner: Float,
    val logoSpan: Int,
    val eyeCorner: Float,
    val solidEyes: Boolean,
)

internal fun rasterizeQr(payload: String, maxPixelSize: Int, style: QrStyle): QrRender {
    val matrix = encodeQrMatrix(payload)
    // A whole number of pixels per module: at fractional cell sizes neighbouring modules end up
    // one pixel wider than each other and the sampling grid a scanner fits drifts off the centres.
    val cell = (maxPixelSize / matrix.width).coerceAtLeast(1)
    val pixelSize = cell * matrix.width
    val geometry = QrGeometry(
        grid = matrix.width,
        cell = cell.toFloat(),
        corner = cell * style.moduleCornerFraction,
        logoSpan = if (style.centerLogo) logoSpanModules(matrix.width - 2 * QUIET_ZONE_MODULES) else 0,
        eyeCorner = if (style.brandedEyes) cell * EYE_CORNER_MODULES else 0f,
        // Rounding every module would round the corner patterns into 49 separate blobs and destroy
        // the run a scanner measures, so a rounded code always gets its eyes painted as solid shapes.
        solidEyes = style.brandedEyes || style.moduleCornerFraction > 0f,
    )
    val bitmap = Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(style.backgroundColor.toArgb())
    val paint = Paint()
    paint.color = style.moduleColor.toArgb()
    paint.style = Paint.Style.FILL
    // Square modules on whole pixels only lose edge contrast to anti-aliasing; round ones need it.
    paint.isAntiAlias = geometry.corner > 0f || style.brandedEyes
    drawModules(canvas, matrix, geometry, paint)
    if (geometry.solidEyes) drawFinderEyes(canvas, geometry, paint)
    val logoPx = (geometry.logoSpan - 2 * LOGO_INSET_MODULES).coerceAtLeast(1) * geometry.cell
    return QrRender(bitmap, pixelSize, if (geometry.logoSpan == 0) 0f else logoPx / pixelSize)
}

private fun drawModules(canvas: AndroidCanvas, matrix: BitMatrix, geometry: QrGeometry, paint: Paint) {
    val rect = RectF()
    for (y in 0 until geometry.grid) {
        for (x in 0 until geometry.grid) {
            val skipped = !matrix[x, y] ||
                (geometry.solidEyes && isFinderModule(x, y, geometry.grid)) ||
                isUnderLogo(x, y, geometry)
            if (skipped) continue
            rect.set(
                x * geometry.cell,
                y * geometry.cell,
                (x + 1) * geometry.cell,
                (y + 1) * geometry.cell,
            )
            canvas.drawRoundRect(rect, geometry.corner, geometry.corner, paint)
        }
    }
}

/** The three 7x7 corner patterns, shifted by the quiet zone the encoder baked into the matrix. */
private fun isFinderModule(x: Int, y: Int, grid: Int): Boolean {
    val near = QUIET_ZONE_MODULES until QUIET_ZONE_MODULES + FINDER_SPAN
    val far = grid - QUIET_ZONE_MODULES - FINDER_SPAN until grid - QUIET_ZONE_MODULES
    return (x in near && y in near) || (x in far && y in near) || (x in near && y in far)
}

private fun isUnderLogo(x: Int, y: Int, geometry: QrGeometry): Boolean {
    if (geometry.logoSpan == 0) return false
    val centre = geometry.grid / 2
    val half = geometry.logoSpan / 2
    return x in centre - half..centre + half && y in centre - half..centre + half
}

/**
 * Repaints each corner pattern as one ring around one pupil. At [EYE_CORNER_MODULES] of radius the
 * straight and diagonal runs through the centre still measure 1:1:3:1:1, which is what a scanner
 * looks for; a square pupil and a square ring reproduce the pattern exactly.
 */
private fun drawFinderEyes(canvas: AndroidCanvas, geometry: QrGeometry, paint: Paint) {
    val ring = Paint(paint)
    ring.style = Paint.Style.STROKE
    ring.strokeWidth = geometry.cell
    val near = QUIET_ZONE_MODULES.toFloat()
    val far = (geometry.grid - QUIET_ZONE_MODULES - FINDER_SPAN).toFloat()
    listOf(near to near, far to near, near to far).forEach { origin ->
        drawEye(canvas, origin, geometry, ring, paint)
    }
}

private fun drawEye(
    canvas: AndroidCanvas,
    origin: Pair<Float, Float>,
    geometry: QrGeometry,
    ring: Paint,
    fill: Paint,
) {
    val cell = geometry.cell
    val (left, top) = origin
    val outer = RectF(left * cell, top * cell, (left + FINDER_SPAN) * cell, (top + FINDER_SPAN) * cell)
    // A stroke one module wide sits astride the rectangle, so inset by half a module to land it
    // exactly on the outer ring of the pattern.
    outer.inset(cell / 2f, cell / 2f)
    canvas.drawRoundRect(outer, geometry.eyeCorner, geometry.eyeCorner, ring)
    val pupilLeft = (left + EYE_PUPIL_OFFSET) * cell
    val pupilTop = (top + EYE_PUPIL_OFFSET) * cell
    val pupil = RectF(pupilLeft, pupilTop, pupilLeft + EYE_PUPIL_SPAN * cell, pupilTop + EYE_PUPIL_SPAN * cell)
    // Radius half the pupil, so a branded eye ends in a circle and a plain one stays square.
    val pupilRadius = if (geometry.eyeCorner > 0f) EYE_PUPIL_SPAN * cell / 2f else 0f
    canvas.drawRoundRect(pupil, pupilRadius, pupilRadius, fill)
}

/** Odd, so the cleared block sits symmetrically on the centre module of an odd grid. */
private fun logoSpanModules(codeModules: Int): Int {
    val span = (codeModules * LOGO_SIDE_FRACTION).toInt()
    return (if (span % 2 == 0) span - 1 else span).coerceAtLeast(1)
}

private fun encodeQrMatrix(payload: String): BitMatrix {
    val hints = mapOf(
        // H recovers 30% of the code, which is what pays for the centre cut-out; at L the logo
        // alone can make a pairing descriptor undecodable. The cost is version 15-17 instead of
        // 9-10, i.e. 77-85 modules instead of 53-57 — hence the larger default render size.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
    )
    return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
}
