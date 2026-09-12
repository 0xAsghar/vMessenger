package ir.vmessenger.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import ir.vmessenger.core.designsystem.R

/**
 * Vazirmatn ships here as Regular + Medium only; there is no Bold asset, so SemiBold and
 * Bold deliberately resolve to the Medium file instead of being synthesised by the system.
 */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_medium, FontWeight.SemiBold),
    Font(R.font.vazirmatn_medium, FontWeight.Bold),
)

private fun vazir(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
): TextStyle = TextStyle(
    fontFamily = Vazirmatn,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

val VMessengerTypography = Typography(
    displayLarge = vazir(size = 57, lineHeight = 64),
    displayMedium = vazir(size = 45, lineHeight = 52),
    displaySmall = vazir(size = 36, lineHeight = 44),
    headlineLarge = vazir(size = 32, lineHeight = 40),
    headlineMedium = vazir(size = 28, lineHeight = 36),
    headlineSmall = vazir(size = 24, lineHeight = 32),
    titleLarge = vazir(size = 22, lineHeight = 28, weight = FontWeight.Medium),
    titleMedium = vazir(size = 16, lineHeight = 24, weight = FontWeight.Medium),
    titleSmall = vazir(size = 14, lineHeight = 20, weight = FontWeight.Medium),
    bodyLarge = vazir(size = 16, lineHeight = 24),
    bodyMedium = vazir(size = 14, lineHeight = 20),
    bodySmall = vazir(size = 12, lineHeight = 16),
    labelLarge = vazir(size = 14, lineHeight = 20, weight = FontWeight.Medium),
    labelMedium = vazir(size = 12, lineHeight = 16, weight = FontWeight.Medium),
    labelSmall = vazir(size = 11, lineHeight = 16, weight = FontWeight.Medium),
)

/** Styles that sit outside the Material scale. */
object VmTextStyles {
    /** Timestamp inside a message bubble: label-small without the extra leading. */
    val bubbleTime = TextStyle(
        fontFamily = Vazirmatn,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 11.sp,
    )
}

val UserHashTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.5.sp,
    textAlign = TextAlign.Center,
)
