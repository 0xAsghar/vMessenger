package ir.vmessenger.core.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The check that was missing when Latin messages rendered with their punctuation on the wrong side.
 *
 * The app forces `LayoutDirection.Rtl` for the whole tree, and it is tempting to assume an
 * unspecified text direction means "decide from the content". It does not: it resolves against the
 * ambient layout direction and yields a hard RTL paragraph. Only [TextDirection.Content] resolves
 * to first-strong. Asserting on the *resolved* value is the point — asserting on the declared one
 * would have passed just as happily before the fix.
 */
class TypographyDirectionTest {

    private fun resolvedIn(style: TextStyle, direction: LayoutDirection): TextDirection =
        resolveDefaults(style, direction).textDirection

    @Test
    fun `every typography style follows its content, not the forced layout direction`() {
        val styles = with(VMessengerTypography) {
            mapOf(
                "displayLarge" to displayLarge, "displayMedium" to displayMedium,
                "displaySmall" to displaySmall, "headlineLarge" to headlineLarge,
                "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
                "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
                "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
                "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
            )
        } + mapOf("bubbleTime" to VmTextStyles.bubbleTime)

        for ((name, style) in styles) {
            assertEquals(
                "$name must resolve to first-strong under RTL",
                TextDirection.ContentOrRtl,
                resolvedIn(style, LayoutDirection.Rtl),
            )
        }
    }

    @Test
    fun `an identity hash is pinned left-to-right whatever surrounds it`() {
        assertEquals(TextDirection.Ltr, resolvedIn(UserHashTextStyle, LayoutDirection.Rtl))
        assertEquals(TextDirection.Ltr, resolvedIn(UserHashTextStyle, LayoutDirection.Ltr))
    }
}
