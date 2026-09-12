package ir.vmessenger.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val VMessengerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Shapes that are not part of the Material scale. */
object VmShapes {
    /**
     * Outgoing bubble: the tail sits on the bottom-**end** corner. Corners are direction
     * relative, so the shape mirrors itself correctly in the app's RTL layout.
     */
    val bubbleOutgoing = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = 4.dp,
        bottomStart = 16.dp,
    )

    /** Incoming bubble: the same shape mirrored, tail on the bottom-start corner. */
    val bubbleIncoming = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = 16.dp,
        bottomStart = 4.dp,
    )

    /** Group avatars are rounded squares so they read differently from person circles. */
    val groupAvatar = RoundedCornerShape(12.dp)

    /** Media thumbnails inside bubbles. */
    val media = RoundedCornerShape(12.dp)
}
