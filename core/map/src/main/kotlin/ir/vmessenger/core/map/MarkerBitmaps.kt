package ir.vmessenger.core.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import ir.vmessenger.core.designsystem.theme.VmColors
import ir.vmessenger.core.designsystem.theme.VmTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import ir.vmessenger.core.designsystem.R as DesignSystemR

/** The theme colours a pin is painted with. A value, so it can key the bitmap cache. */
@Immutable
data class MarkerChrome(
    val surface: Int,
    val onSurface: Int,
    val outline: Int,
    val accent: Int,
)

/**
 * Builds and caches one bitmap per contact pin.
 *
 * The contact's name is *baked into the bitmap* with [StaticLayout] and the Vazirmatn typeface
 * rather than drawn by the map's own text engine: Persian shaping and bidi then work regardless
 * of which glyphs the vector style happens to ship.
 *
 * The cache key is [MapMarker.iconKey] — colour seed plus label — so a contact that is merely
 * moving reuses its pin forever; only a rename or a new contact allocates.
 */
class MarkerBitmaps internal constructor(
    private val context: Context,
    private val colors: VmColors,
    val chrome: MarkerChrome,
) {
    private val density = context.resources.displayMetrics.density
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)
    private val paletteTag = chrome.hashCode().toString(HEX_RADIX_TAG)

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, DesignSystemR.font.vazirmatn_medium) ?: Typeface.DEFAULT
        textSize = LABEL_SP * density
        color = chrome.onSurface
    }

    /**
     * Style image name. It carries the palette so a light/dark switch registers new images
     * instead of silently reusing the ones drawn for the old theme.
     */
    fun imageId(marker: MapMarker): String = "vm-pin-$paletteTag-${marker.iconKey}"

    fun bitmap(marker: MapMarker): Bitmap =
        cache.get(marker.iconKey) ?: build(marker).also { cache.put(marker.iconKey, it) }

    private fun build(marker: MapMarker): Bitmap {
        val layout = labelLayout(marker.label)
        val ring = RING_DP * density
        val chipHeight = layout.height + 2 * CHIP_PADDING_DP * density
        val chipWidth = layout.width + 2 * CHIP_PADDING_H_DP * density
        val disc = AVATAR_DP * density
        val pointer = POINTER_H_DP * density
        val width = ceil(max(chipWidth, disc)).toInt()
        val height = ceil(chipHeight + GAP_DP * density + disc + pointer).toInt()
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = width / 2f
        val accent = accentFor(marker)
        canvas.drawLabelChip(
            rect = RectF(center - chipWidth / 2f, 0f, center + chipWidth / 2f, chipHeight),
            layout = layout,
            radius = chipHeight / 2f,
            chrome = chrome,
        )
        val discTop = chipHeight + GAP_DP * density
        val pointerHalf = POINTER_W_DP * density / 2f
        canvas.drawPointer(
            RectF(center - pointerHalf, discTop + disc - ring, center + pointerHalf, height.toFloat()),
            chrome,
        )
        val bounds = RectF(center - disc / 2f, discTop, center + disc / 2f, discTop + disc)
        canvas.drawAvatarDisc(bounds, marker.seedHex.hexToBytes(), accent, chrome, ring)
        if (marker.seedHex.isEmpty()) canvas.drawInitial(bounds, marker.label, accent)
        return bitmap
    }

    /** The same per-identity colour the design system's avatars use. */
    private fun accentFor(marker: MapMarker): Int {
        val seed = marker.seedHex.hexToBytes()
        return if (seed.isEmpty()) chrome.accent else colors.senderColor(seed).toArgb()
    }

    private fun labelLayout(label: String): StaticLayout {
        val text = label.trim().ifEmpty { " " }
        val maxWidth = (LABEL_MAX_DP * density).toInt()
        val desired = ceil(Layout.getDesiredWidth(text, labelPaint)).toInt()
        val width = max(min(desired, maxWidth), 1)
        return StaticLayout.Builder.obtain(text, 0, text.length, labelPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(width)
            .setMaxLines(1)
            .setIncludePad(false)
            .build()
    }

    private fun Canvas.drawInitial(bounds: RectF, label: String, accent: Int) {
        val paint = TextPaint(labelPaint).apply {
            color = accent
            textSize = bounds.width() * INITIAL_RATIO
            textAlign = Paint.Align.CENTER
        }
        val baseline = bounds.centerY() - (paint.descent() + paint.ascent()) / 2f
        drawText(label.trim().take(1), bounds.centerX(), baseline, paint)
    }

    private companion object {
        const val HEX_RADIX_TAG = 16
        const val CACHE_ENTRIES = 32
        const val LABEL_SP = 11f
        const val LABEL_MAX_DP = 120f
        const val CHIP_PADDING_DP = 3f
        const val CHIP_PADDING_H_DP = 7f
        const val AVATAR_DP = 34f
        const val RING_DP = 2f
        const val GAP_DP = 3f
        const val POINTER_H_DP = 7f
        const val POINTER_W_DP = 11f
        const val INITIAL_RATIO = 0.42f
    }
}

/**
 * One cache per theme. A light/dark switch produces a new instance, which re-registers the pin
 * images with the newly loaded style; within a theme the same instance is reused forever.
 */
@Composable
fun rememberMarkerBitmaps(): MarkerBitmaps {
    val context = LocalContext.current
    val colors = VmTheme.colors
    val chrome = MarkerChrome(
        surface = colors.bgElevated.toArgb(),
        onSurface = colors.textPrimary.toArgb(),
        outline = colors.borderSubtle.toArgb(),
        accent = colors.bgAccent.toArgb(),
    )
    // The application context: this object outlives the composition that made it.
    val appContext = context.applicationContext
    return remember(appContext, colors, chrome) { MarkerBitmaps(appContext, colors, chrome) }
}

/** Accuracy colours derived from the pin chrome; kept here so the layer has no theme knowledge. */
internal fun MarkerChrome.accuracyFill(): Int =
    Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent))

internal fun MarkerChrome.accuracyStroke(): Int =
    Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
