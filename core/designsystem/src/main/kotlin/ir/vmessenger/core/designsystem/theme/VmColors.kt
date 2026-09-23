package ir.vmessenger.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colours, named for what they do rather than where Material would put them.
 *
 * The visual language follows Element X: a plain canvas, cool neutral greys doing nearly all the
 * work, primary actions in the text colour itself — near-black on light, near-white on dark — and
 * one accent, used sparingly, for the things that should catch the eye: links, unread, the user's
 * own messages, a toggle that is on. The accent is vMessenger's teal rather than Element's green.
 *
 * Nothing here is copied from Element's design tokens (they are AGPL-3.0); the values are this
 * app's own, chosen to read the same way.
 *
 * Every token exists in a light and a dark value, and the dark ones are not an inversion: dark
 * surfaces step *up* in lightness as they come forward, and text on them is off-white, never pure
 * white, so a long conversation at night does not glare.
 */
@Immutable
@Suppress("LongParameterList") // A token bundle: every colour is an independent design decision.
class VmColors(
    val isDark: Boolean,
    // Backgrounds, back to front.
    /** The screen itself. */
    val bgCanvas: Color,
    /** A quiet fill: an input, a pressed row, an incoming bubble. */
    val bgSubtle: Color,
    /** A fill that has to be seen as one: a selected chip, a skeleton, a highlighted row. */
    val bgSubtleStrong: Color,
    /** What floats above the canvas: dialogs, sheets, menus, snackbars' surroundings. */
    val bgElevated: Color,
    /** Primary actions: the filled button, the FAB. The text colour, used as a fill. */
    val bgActionPrimary: Color,
    val bgActionPrimaryDisabled: Color,
    /** The accent as a fill: badges, the send button, an active toggle track. */
    val bgAccent: Color,
    /** The accent, barely there: the user's own messages, a selected list row. */
    val bgAccentSubtle: Color,
    val bgCritical: Color,
    val bgCriticalSubtle: Color,
    val bgWarningSubtle: Color,
    val bgInfoSubtle: Color,
    /** Behind a modal sheet or dialog. */
    val scrim: Color,
    // Text.
    val textPrimary: Color,
    val textSecondary: Color,
    val textPlaceholder: Color,
    val textDisabled: Color,
    /** On [bgActionPrimary]. */
    val textOnActionPrimary: Color,
    /** On [bgAccent] and [bgCritical]. */
    val textOnSolid: Color,
    val textAccent: Color,
    /** The accent on an inverse surface — a snackbar's action, which sits on the text colour. */
    val textAccentInverse: Color,
    val textCritical: Color,
    val textSuccess: Color,
    val textWarning: Color,
    val textInfo: Color,
    // Icons.
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconTertiary: Color,
    val iconDisabled: Color,
    val iconAccent: Color,
    val iconCritical: Color,
    val iconSuccess: Color,
    // Lines.
    /** Dividers and card edges: present, never loud. */
    val borderSubtle: Color,
    /** The edge of something you can act on: a text field, an outlined button. */
    val borderInteractive: Color,
    /** The edge of the thing that has focus. */
    val borderFocused: Color,
    val borderCritical: Color,
    // Conversation.
    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    val bubbleIncoming: Color,
    val onBubbleIncoming: Color,
    val tickPending: Color,
    val tickSent: Color,
    val tickRead: Color,
    val chatBackground: Color,
    val senderPalette: List<Color>,
    val recordingRed: Color,
    val keyChangeWarning: Color,
    /** Briefly tints a bubble that was just jumped to, so the eye can find it. */
    val bubbleHighlight: Color,
) {
    /** Stable colour for a sender, derived from its identity hash. */
    fun senderColor(seed: ByteArray): Color {
        if (senderPalette.isEmpty()) return onBubbleIncoming
        var acc = 0
        for (byte in seed) {
            acc = (acc * 31 + (byte.toInt() and 0xFF)) and 0x7FFFFFFF
        }
        return senderPalette[acc % senderPalette.size]
    }
}

// Neutrals ---------------------------------------------------------------------------------------
private val Ink = Color(0xFF1B1D22)
private val Grey700 = Color(0xFF656D77)
private val Grey500 = Color(0xFF8D97A5)
private val Grey400 = Color(0xFFA6ADB6)
private val Grey300 = Color(0xFFCDD3DA)
private val Grey200 = Color(0xFFE1E6EC)
private val Grey100 = Color(0xFFF0F2F5)
private val Paper = Color(0xFFFFFFFF)

private val Night = Color(0xFF101317)
private val Night100 = Color(0xFF1B1E23)
private val Night200 = Color(0xFF25282E)
private val Night300 = Color(0xFF363A40)
private val Night500 = Color(0xFF6F7882)
private val Night600 = Color(0xFF9199A4)
private val Mist = Color(0xFFEBEEF2)

// Accent: vMessenger's teal ----------------------------------------------------------------------
private val Teal700 = Color(0xFF0F766E)
private val Teal050 = Color(0xFFE5F3F1)
private val Teal300 = Color(0xFF4FD1BE)
private val Teal900 = Color(0xFF123733)

/** Muted, mutually distinguishable hues for group sender names (light theme). */
private val LightSenderPalette = listOf(
    Color(0xFF9A5B2E),
    Color(0xFF8A4B6B),
    Color(0xFF4A5E8C),
    Color(0xFF3F6B5A),
    Color(0xFF7A5C9E),
    Color(0xFF8C6A2E),
    Color(0xFF2F6E7A),
    Color(0xFF8C4A4A),
)

/** Same eight hues lightened so they stay readable on the dark canvas. */
private val DarkSenderPalette = listOf(
    Color(0xFFD9A579),
    Color(0xFFD69CBE),
    Color(0xFF9DB3E0),
    Color(0xFF8FC3AE),
    Color(0xFFC3AEE0),
    Color(0xFFD9C07E),
    Color(0xFF86C6D1),
    Color(0xFFDDA0A0),
)

val VmLightColors = VmColors(
    isDark = false,
    bgCanvas = Paper,
    bgSubtle = Grey100,
    bgSubtleStrong = Grey200,
    bgElevated = Paper,
    bgActionPrimary = Ink,
    bgActionPrimaryDisabled = Grey300,
    bgAccent = Teal700,
    bgAccentSubtle = Teal050,
    bgCritical = Color(0xFFD51928),
    bgCriticalSubtle = Color(0xFFFDECEC),
    bgWarningSubtle = Color(0xFFFFF3E0),
    bgInfoSubtle = Color(0xFFEAF2FE),
    scrim = Color(0x66000000),
    textPrimary = Ink,
    textSecondary = Grey700,
    textPlaceholder = Grey500,
    textDisabled = Grey400,
    textOnActionPrimary = Paper,
    textOnSolid = Paper,
    textAccent = Teal700,
    textAccentInverse = Teal300,
    textCritical = Color(0xFFD51928),
    textSuccess = Color(0xFF007A61),
    textWarning = Color(0xFF8A4B00),
    textInfo = Color(0xFF0467DD),
    iconPrimary = Ink,
    iconSecondary = Grey700,
    iconTertiary = Grey500,
    iconDisabled = Grey300,
    iconAccent = Teal700,
    iconCritical = Color(0xFFD51928),
    iconSuccess = Color(0xFF007A61),
    borderSubtle = Grey200,
    borderInteractive = Grey300,
    borderFocused = Ink,
    borderCritical = Color(0xFFD51928),
    bubbleOutgoing = Teal050,
    onBubbleOutgoing = Ink,
    bubbleIncoming = Grey100,
    onBubbleIncoming = Ink,
    tickPending = Grey500,
    tickSent = Grey500,
    tickRead = Teal700,
    chatBackground = Paper,
    senderPalette = LightSenderPalette,
    recordingRed = Color(0xFFD51928),
    keyChangeWarning = Color(0xFFFFF3E0),
    bubbleHighlight = Grey200,
)

val VmDarkColors = VmColors(
    isDark = true,
    bgCanvas = Night,
    bgSubtle = Night100,
    bgSubtleStrong = Night200,
    bgElevated = Night100,
    bgActionPrimary = Mist,
    bgActionPrimaryDisabled = Night300,
    bgAccent = Teal300,
    bgAccentSubtle = Teal900,
    bgCritical = Color(0xFFE5484D),
    bgCriticalSubtle = Color(0xFF3A1C1D),
    bgWarningSubtle = Color(0xFF392A14),
    bgInfoSubtle = Color(0xFF16263D),
    scrim = Color(0x8C000000),
    textPrimary = Mist,
    textSecondary = Night600,
    textPlaceholder = Night500,
    textDisabled = Color(0xFF535961),
    textOnActionPrimary = Night,
    textOnSolid = Color(0xFF06201D),
    textAccent = Teal300,
    textAccentInverse = Teal700,
    textCritical = Color(0xFFFF7A76),
    textSuccess = Color(0xFF1FC090),
    textWarning = Color(0xFFF0B45A),
    textInfo = Color(0xFF6CA3F5),
    iconPrimary = Mist,
    iconSecondary = Night600,
    iconTertiary = Night500,
    iconDisabled = Night300,
    iconAccent = Teal300,
    iconCritical = Color(0xFFFF7A76),
    iconSuccess = Color(0xFF1FC090),
    borderSubtle = Night200,
    borderInteractive = Night300,
    borderFocused = Mist,
    borderCritical = Color(0xFFFF7A76),
    bubbleOutgoing = Teal900,
    onBubbleOutgoing = Mist,
    bubbleIncoming = Night100,
    onBubbleIncoming = Mist,
    tickPending = Night500,
    tickSent = Night500,
    tickRead = Teal300,
    chatBackground = Night,
    senderPalette = DarkSenderPalette,
    recordingRed = Color(0xFFE5484D),
    keyChangeWarning = Color(0xFF392A14),
    bubbleHighlight = Night200,
)

val LocalVmColors = staticCompositionLocalOf { VmLightColors }
