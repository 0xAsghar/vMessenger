package ir.vmessenger.core.designsystem.component

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * How a code is painted. One object rather than loose parameters so [StyledQrCode] stays well
 * under the parameter limit, and so the branded treatment is something a screen opts into.
 *
 * Colours stay dark-on-light whatever the app theme is doing: inverted (light-on-dark) codes fail
 * on many scanners, and the quiet zone has to be light.
 */
@Immutable
data class QrStyle(
    val moduleCornerFraction: Float = 0f,
    val brandedEyes: Boolean = false,
    val centerLogo: Boolean = false,
    val moduleColor: Color = Color.Black,
    val backgroundColor: Color = Color.White,
) {
    companion object {
        /** Square modules, square eyes, no logo: the shape every scanner was built against. */
        val Plain = QrStyle()

        /**
         * The pairing look: rounded modules, vMessenger eyes, the mark knocked out of the centre.
         * Only safe because the encoder runs at error-correction level H.
         */
        val Branded = QrStyle(moduleCornerFraction = 0.34f, brandedEyes = true, centerLogo = true)
    }
}
