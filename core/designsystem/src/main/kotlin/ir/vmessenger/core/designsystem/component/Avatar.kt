package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmTheme

private const val WASH_ALPHA = 0.16f
private const val SECONDARY_ALPHA = 0.55f

/** The fallback letter's size as a share of the avatar's, so it fills a large avatar as it does a small one. */
private const val LETTER_FRACTION = 0.42f

/**
 * Identity picture for a contact or a group.
 *
 * The colour is derived from [seed] via `VmColors.senderPalette`, so the same identity always
 * gets the same avatar. When [seed] is empty (an unknown group member, say) the first letter of
 * [name] is drawn instead. [contentDescription] is null by default because avatars almost always
 * sit next to the name they belong to; pass a value when the avatar stands alone.
 */
@Suppress("LongParameterList") // Compose slot API: every argument is an independent knob.
@Composable
fun Avatar(
    seed: ByteArray,
    name: String,
    size: Dp = VmSizes.avatarMd,
    variant: AvatarVariant = AvatarVariant.Person,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val base = VmTheme.colors.senderColor(seed)
    val shape = if (variant == AvatarVariant.Group) VmShapes.groupAvatar else CircleShape
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(base.copy(alpha = WASH_ALPHA))
            .clearAndSetSemantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        if (seed.isEmpty()) {
            // Sized from the avatar in dp, so a large font setting cannot push the letter out of it.
            val letterSize = with(LocalDensity.current) { (size * LETTER_FRACTION).toSp() }
            VmText(
                text = name.trim().take(1),
                style = VmTheme.typography.bodyLgMedium.copy(fontSize = letterSize, lineHeight = letterSize),
                color = base,
            )
        } else {
            Identicon(
                seed = seed,
                colors = listOf(base, base.copy(alpha = SECONDARY_ALPHA)),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
