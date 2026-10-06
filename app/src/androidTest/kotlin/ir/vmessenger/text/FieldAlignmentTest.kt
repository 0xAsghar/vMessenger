package ir.vmessenger.text

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import ir.vmessenger.core.designsystem.component.Composer
import ir.vmessenger.core.designsystem.component.ComposerState
import ir.vmessenger.core.designsystem.component.VmSearchBar
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.theme.VMessengerTheme
import ir.vmessenger.core.designsystem.theme.VmTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Where typed text starts in a field, measured on the pixels a device really draws.
 *
 * Reported on a phone: with the app in Persian, English typed into a field began in the middle of it,
 * and with the app in English, so did Persian. The app's text direction follows the content (see
 * `TypographyDirectionTest`), so English belongs at the left edge of a field and Persian at the right,
 * whichever way the screen runs. Text that runs the opposite way from the layout was being laid out
 * inside a node only as wide as ten characters, parked at the layout's start edge: it landed at that
 * node's far edge, which is the middle of the field.
 *
 * The positions are asserted from a screenshot of the composable, because the bug is where glyphs sit,
 * not any value the code declares.
 */
class FieldAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    private val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    // ---- the chat composer ------------------------------------------------------------------------
    // Row: 4 dp inset, a 48 dp attach button, 2 dp gap, the pill, 2 dp gap, a 48 dp send button, 4 dp
    // inset. The pill pads its text 16 dp, so the text area is [70, 290] dp whichever way it runs.

    @Test
    fun composer_english_in_a_persian_screen_starts_at_the_left_edge() =
        assertStartsAtLeft(composerInk(LayoutDirection.Rtl, ENGLISH), expectedLeft = 70f)

    @Test
    fun composer_persian_in_an_english_screen_starts_at_the_right_edge() =
        assertStartsAtRight(composerInk(LayoutDirection.Ltr, PERSIAN), expectedRight = 290f)

    @Test
    fun composer_english_in_an_english_screen_starts_at_the_left_edge() =
        assertStartsAtLeft(composerInk(LayoutDirection.Ltr, ENGLISH), expectedLeft = 70f)

    @Test
    fun composer_persian_in_a_persian_screen_starts_at_the_right_edge() =
        assertStartsAtRight(composerInk(LayoutDirection.Rtl, PERSIAN), expectedRight = 290f)

    private fun composerInk(direction: LayoutDirection, text: String): Ink {
        val bitmap = render("composer-${direction.name}-${tag(text)}", direction) {
            Composer(state = ComposerState(text = text), onTextChange = {}, onSend = {}, onAttach = {})
        }
        // The window's insets make the host taller than the bar, so look at every row: nothing but the
        // text is dark between the two buttons.
        return ink(bitmap, fromDp = 56f, toDp = 304f, wholeHeight = true)
    }

    // ---- the shared text field --------------------------------------------------------------------
    // 16 dp of inset inside a 1 dp edge: the text area is [16, 344] dp.

    @Test
    fun field_english_in_a_persian_screen_starts_at_the_left_edge() =
        assertStartsAtLeft(fieldInk(LayoutDirection.Rtl, ENGLISH), expectedLeft = 16f)

    @Test
    fun field_persian_in_an_english_screen_starts_at_the_right_edge() =
        assertStartsAtRight(fieldInk(LayoutDirection.Ltr, PERSIAN), expectedRight = 344f)

    @Test
    fun field_persian_in_a_persian_screen_starts_at_the_right_edge() =
        assertStartsAtRight(fieldInk(LayoutDirection.Rtl, PERSIAN), expectedRight = 344f)

    private fun fieldInk(direction: LayoutDirection, text: String): Ink {
        val bitmap = render("field-${direction.name}-${tag(text)}", direction) {
            VmTextField(value = text, onValueChange = {}, modifier = Modifier.fillMaxWidth())
        }
        return ink(bitmap, fromDp = 6f, toDp = HOST_WIDTH_DP - 6f)
    }

    // ---- the search bar ---------------------------------------------------------------------------
    // The pill keeps a 12 dp inset at its end, holds a 44 dp back button at its start and, with a query,
    // a 44 dp clear button at its end: the text area is [44, 304] dp in English and [56, 316] in Persian.

    @Test
    fun search_english_in_a_persian_screen_starts_at_the_left_edge() =
        assertStartsAtLeft(searchInk(LayoutDirection.Rtl, ENGLISH), expectedLeft = 56f)

    @Test
    fun search_persian_in_an_english_screen_starts_at_the_right_edge() =
        assertStartsAtRight(searchInk(LayoutDirection.Ltr, PERSIAN), expectedRight = 304f)

    private fun searchInk(direction: LayoutDirection, text: String): Ink {
        val bitmap = render("search-${direction.name}-${tag(text)}", direction) {
            VmSearchBar(query = text, onQueryChange = {}, onClose = {})
        }
        val (from, to) = if (direction == LayoutDirection.Ltr) 46f to 302f else 58f to 314f
        return ink(bitmap, fromDp = from, toDp = to)
    }

    // ---- measuring --------------------------------------------------------------------------------

    private class Ink(val left: Float, val right: Float)

    /** Draws [content] in a [HOST_WIDTH_DP]-wide host, and returns a screenshot of the host. */
    private fun render(name: String, direction: LayoutDirection, content: @Composable () -> Unit): Bitmap {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                VMessengerTheme(darkTheme = false) {
                    Box(
                        modifier = Modifier
                            .width(HOST_WIDTH_DP.dp)
                            .background(VmTheme.colors.bgCanvas)
                            .testTag(HOST),
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(HOST).captureToImage().asAndroidBitmap()
        save(name, bitmap)
        return bitmap
    }

    /**
     * The left and right extent, in dp from the host's left edge, of dark pixels in the middle band of
     * the host, or in all of it.
     */
    private fun ink(bitmap: Bitmap, fromDp: Float, toDp: Float, wholeHeight: Boolean = false): Ink {
        val top = if (wholeHeight) 0 else max(0, (bitmap.height / 2f - BAND_DP * density).toInt())
        val bottom = if (wholeHeight) {
            bitmap.height - 1
        } else {
            min(bitmap.height - 1, (bitmap.height / 2f + BAND_DP * density).toInt())
        }
        var left = Int.MAX_VALUE
        var right = -1
        for (x in (fromDp * density).toInt()..min(bitmap.width - 1, (toDp * density).toInt())) {
            for (y in top..bottom) {
                val p = bitmap.getPixel(x, y)
                val luminance = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (luminance < DARK) {
                    left = min(left, x)
                    right = max(right, x)
                    break
                }
            }
        }
        check(right >= 0) { "no text was drawn in the band" }
        return Ink(left = left / density, right = (right + 1) / density)
    }

    private fun assertStartsAtLeft(ink: Ink, expectedLeft: Float) = assertTrue(
        "text should start at the left edge (${expectedLeft.toInt()} dp) but its ink spans " +
            "${ink.left} to ${ink.right} dp",
        ink.left <= expectedLeft + TOLERANCE_DP,
    )

    private fun assertStartsAtRight(ink: Ink, expectedRight: Float) = assertTrue(
        "text should start at the right edge (${expectedRight.toInt()} dp) but its ink spans " +
            "${ink.left} to ${ink.right} dp",
        ink.right >= expectedRight - TOLERANCE_DP,
    )

    /** A picture of each case beside the app's files, so the result can be looked at, not only asserted. */
    private fun save(name: String, bitmap: Bitmap) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("field-alignment")
            ?: return
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tag(text: String) = if (text == ENGLISH) "english" else "persian"

    private companion object {
        const val HOST = "host"
        const val HOST_WIDTH_DP = 360f
        const val BAND_DP = 8f
        const val DARK = 110
        const val TOLERANCE_DP = 8f
        const val ENGLISH = "Test"
        const val PERSIAN = "فارسی"
    }
}
