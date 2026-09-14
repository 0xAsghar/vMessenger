package ir.vmessenger.core.designsystem.theme

import androidx.compose.ui.unit.dp

/** The only spacing values the app is allowed to use. */
object VmSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Fixed component dimensions shared across screens. */
object VmSizes {
    val avatarSm = 36.dp
    val avatarMd = 48.dp
    val avatarLg = 72.dp
    val listItemHeight = 72.dp
    const val bubbleMaxWidthFraction = 0.78f
    val touchTarget = 48.dp

    /** An icon set inside a line of text: a delivery tick, a mute bell, an inline spinner. */
    val iconSm = 16.dp

    /** An icon that shares a row with a label of its own: the spinner inside a button. */
    val iconMd = 20.dp

    /** An icon that is the content rather than a decoration: an attachment tile's glyph. */
    val iconLg = 28.dp

    /** The single large glyph an otherwise-empty screen is allowed to show. */
    val emptyStateIcon = 56.dp

    /** A progress ring keeps this stroke at every diameter, or it stops reading as one ring. */
    val progressStroke = 2.dp
}
