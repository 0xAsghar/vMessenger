package ir.vmessenger.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import ir.vmessenger.core.designsystem.R

/**
 * Vazirmatn 33.003, as Regular, Medium and Bold — all three the real faces, none synthesised.
 *
 * Bold is here because the mapping without it was a silent substitution: `FontWeight.Bold`
 * resolved to the Medium file, so anyone writing bold text got Medium and no warning. Persian
 * absorbed that, since the type scale below asks only for Normal and Medium; an English hierarchy
 * leaning on bold would have read flat with nothing to point at.
 *
 * SemiBold still resolves to Medium. Nothing in the app requests it, and vendoring a fourth
 * 120 KB face for a weight no style uses would be dead weight — but if a style ever does ask for
 * SemiBold, add `Vazirmatn-SemiBold.ttf` from the same release rather than leaving it approximated.
 */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_medium, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

/**
 * Every style in the app is built here, which is why the bidi fix is a single line.
 *
 * [TextDirection.Content] is NOT the default it looks like. The app forces `LayoutDirection.Rtl`
 * for the whole tree, and an unspecified text direction resolves against that to a *hard* RTL
 * paragraph — so a Latin message took the paragraph's direction for its trailing neutrals and
 * "halo bar?" rendered as "?halo bar". Content resolves from the first strong character instead,
 * which is what the map's pin labels have always done via FIRSTSTRONG_RTL.
 *
 * [LineHeightStyle] distributes the leading rather than trimming it off the descender side;
 * Vazirmatn's descenders are deep and were being clipped wherever a parent constrained height.
 */
private fun vazir(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
): TextStyle = TextStyle(
    fontFamily = Vazirmatn,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    textDirection = TextDirection.Content,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * The app's type scale: four headings and four body sizes, each body size in a regular and a medium
 * weight. Deliberately smaller than Material's fifteen roles — Element X runs on a scale this size,
 * and a screen reads as designed when every piece of text on it comes from a short list.
 *
 * Sizes are a notch below Element's Inter values because Vazirmatn sets larger at the same point
 * size; line heights are more generous than Latin-only scales need, because Persian's descenders
 * and diacritics need the room.
 */
@Immutable
@Suppress("LongParameterList") // A token bundle: one style per step of the scale.
class VmTypography(
    /** A screen's single largest line: onboarding headlines, the name on a profile. */
    val headingXl: TextStyle,
    val headingLg: TextStyle,
    /** Section-sized headlines: a dialog title, an empty state. */
    val headingMd: TextStyle,
    /** Top bar titles. */
    val headingSm: TextStyle,
    /** List row titles, message text, large buttons. */
    val bodyLg: TextStyle,
    val bodyLgMedium: TextStyle,
    /** The default for running text. */
    val bodyMd: TextStyle,
    val bodyMdMedium: TextStyle,
    /** Supporting text, captions, timestamps in lists. */
    val bodySm: TextStyle,
    val bodySmMedium: TextStyle,
    /** The smallest thing on screen: badges, timestamps inside bubbles. */
    val bodyXs: TextStyle,
    val bodyXsMedium: TextStyle,
)

val VmDefaultTypography = VmTypography(
    headingXl = vazir(size = 30, lineHeight = 44, weight = FontWeight.Bold),
    headingLg = vazir(size = 26, lineHeight = 38, weight = FontWeight.Bold),
    headingMd = vazir(size = 22, lineHeight = 32, weight = FontWeight.Bold),
    headingSm = vazir(size = 19, lineHeight = 28, weight = FontWeight.Medium),
    bodyLg = vazir(size = 16, lineHeight = 26),
    bodyLgMedium = vazir(size = 16, lineHeight = 26, weight = FontWeight.Medium),
    bodyMd = vazir(size = 14, lineHeight = 22),
    bodyMdMedium = vazir(size = 14, lineHeight = 22, weight = FontWeight.Medium),
    bodySm = vazir(size = 12, lineHeight = 18),
    bodySmMedium = vazir(size = 12, lineHeight = 18, weight = FontWeight.Medium),
    bodyXs = vazir(size = 11, lineHeight = 16),
    bodyXsMedium = vazir(size = 11, lineHeight = 16, weight = FontWeight.Medium),
)

val LocalVmTypography = staticCompositionLocalOf { VmDefaultTypography }

/** Styles that sit outside the scale. */
object VmTextStyles {
    /** Timestamp inside a message bubble: tighter than label-small, but not tighter than the font. */
    val bubbleTime = TextStyle(
        fontFamily = Vazirmatn,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        textDirection = TextDirection.Content,
    )
}

/**
 * Ltr rather than Content: an identity hash is an opaque identifier, not prose, and must never take
 * its direction from what happens to be inside it. `vm2-ABCDE-12345-…` mixes Latin letters, digits
 * and neutral hyphens, and under a forced-RTL paragraph the digit groups and their separators
 * visually reordered — so two people comparing hashes out of band saw different strings.
 */
val UserHashTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.5.sp,
    textAlign = TextAlign.Center,
    textDirection = TextDirection.Ltr,
)
