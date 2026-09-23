package ir.vmessenger.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Every corner in the app.
 *
 * Buttons, chips and the search field are fully rounded — the Element X shape language, where
 * anything you press is a pill and anything you read is a softly rounded block. Corners are
 * direction-relative throughout, so each shape mirrors itself in the RTL layout.
 */
object VmShapes {
    /** Buttons, chips, badges, the search field. */
    val pill = CircleShape

    /** Text fields and small blocks of content. */
    val field = RoundedCornerShape(12.dp)

    /** Cards and grouped sections. */
    val card = RoundedCornerShape(16.dp)

    /** Dialogs. */
    val dialog = RoundedCornerShape(24.dp)

    /** Menus and popups. */
    val menu = RoundedCornerShape(12.dp)

    /** A modal or persistent bottom sheet: rounded where it meets the content above it. */
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    /** Snackbars. */
    val snackbar = RoundedCornerShape(12.dp)

    /**
     * Outgoing bubble: the tail sits on the bottom-**end** corner. Corners are direction
     * relative, so the shape mirrors itself correctly in the app's RTL layout.
     */
    val bubbleOutgoing = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomEnd = 6.dp,
        bottomStart = 18.dp,
    )

    /** Incoming bubble: the same shape mirrored, tail on the bottom-start corner. */
    val bubbleIncoming = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomEnd = 18.dp,
        bottomStart = 6.dp,
    )

    /** Group avatars are rounded squares so they read differently from person circles. */
    val groupAvatar = RoundedCornerShape(12.dp)

    /** Media thumbnails inside bubbles. */
    val media = RoundedCornerShape(14.dp)
}
