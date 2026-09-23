package ir.vmessenger.text

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.theme.RtlLayout
import ir.vmessenger.core.designsystem.theme.VMessengerTheme
import ir.vmessenger.core.designsystem.theme.VmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The bidi fix, asserted on a real text layout rather than on a declared style.
 *
 * The JVM test beside the typography checks that `TextDirection.Content` survives resolution; only
 * a device can answer the question the user actually asked, which is which way the glyphs run. The
 * app forces `LayoutDirection.Rtl` for the whole tree, and before the fix every paragraph came out
 * right-to-left regardless of content — so a Latin message's trailing punctuation jumped to the
 * wrong side and "halo bar?" read as "?halo bar".
 */
class BidiRenderingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_latin_message_lays_out_left_to_right_inside_the_rtl_app() {
        assertEquals(ResolvedTextDirection.Ltr, paragraphDirectionOf("halo bar?"))
    }

    @Test
    fun a_persian_message_still_lays_out_right_to_left() {
        assertEquals(ResolvedTextDirection.Rtl, paragraphDirectionOf("سلام دوست من؟"))
    }

    @Test
    fun a_persian_sentence_led_by_an_isolated_latin_name_keeps_its_own_direction() {
        // The half that had to ship with the direction change: without the isolate, the Latin name
        // becomes the paragraph's first strong character and flips the whole Persian line.
        val isolated = "⁨Ali⁩ به گروه اضافه شد"
        assertEquals(ResolvedTextDirection.Rtl, paragraphDirectionOf(isolated))
    }

    /** Renders [text] the way a message bubble does and reports how the first paragraph ran. */
    private fun paragraphDirectionOf(text: String): ResolvedTextDirection {
        var layout: TextLayoutResult? = null
        compose.setContent {
            RtlLayout {
                VMessengerTheme(darkTheme = false) {
                    VmText(
                        text = text,
                        style = VmTheme.typography.bodyLg,
                        onTextLayout = { layout = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        return requireNotNull(layout).getParagraphDirection(0)
    }
}
