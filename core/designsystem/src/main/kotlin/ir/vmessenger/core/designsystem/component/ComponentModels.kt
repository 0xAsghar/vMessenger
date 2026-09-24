package ir.vmessenger.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmTheme

/** Person avatars are circles; group avatars are rounded squares. */
enum class AvatarVariant { Person, Group }

/** The five states a message can be in, in the order they are reached. */
enum class DeliveryTicksState { QUEUED, SENT, DELIVERED, READ, FAILED }

/** Which side of the conversation a bubble belongs to. */
enum class BubbleDirection { Incoming, Outgoing }

/** Container and content colours of a single message bubble. */
@Immutable
data class MessageBubbleColors(val container: Color, val content: Color)

/** Theme-derived defaults for [MessageBubble]. */
object MessageBubbleDefaults {
    @Composable
    @ReadOnlyComposable
    fun colors(direction: BubbleDirection): MessageBubbleColors {
        val vm = VmTheme.colors
        return if (direction == BubbleDirection.Outgoing) {
            MessageBubbleColors(container = vm.bubbleOutgoing, content = vm.onBubbleOutgoing)
        } else {
            MessageBubbleColors(container = vm.bubbleIncoming, content = vm.onBubbleIncoming)
        }
    }
}

/** The message a composer draft or a bubble is replying to. */
@Immutable
data class ReplyPreview(
    val messageId: String,
    val senderName: String,
    val preview: String,
)

/** Everything [Composer] needs to draw itself; the screen owns the state. */
@Immutable
data class ComposerState(
    val text: String = "",
    val enabled: Boolean = true,
    val recording: Boolean = false,
) {
    val canSend: Boolean get() = enabled && !recording && text.isNotBlank()
}

/** Optional call to action under an [EmptyState]. */
@Stable
data class EmptyStateAction(val label: String, val onClick: () -> Unit)

/** The static appearance and behaviour of a [VmTextField]; the screen owns the mutable text itself. */
@Immutable
data class VmTextFieldConfig(
    val label: String? = null,
    val placeholder: String? = null,
    val enabled: Boolean = true,
    val isError: Boolean = false,
    val supportingText: String? = null,
    val singleLine: Boolean = true,
    val isPassword: Boolean = false,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val imeAction: ImeAction = ImeAction.Default,
    /** Shows [supportingText] in the success colour: an input that has been checked and is good. */
    val supportingIsSuccess: Boolean = false,
    val minLines: Int = 1,
    val maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    val readOnly: Boolean = false,
    val capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    /** How the text is drawn, without changing what the field holds; [isPassword] takes precedence. */
    val visualTransformation: VisualTransformation = VisualTransformation.None,
)

/** How much room a button takes. Large for a screen's main action, medium inside dialogs and rows. */
enum class VmButtonSize(val height: Dp, val horizontalPadding: Dp, val iconSize: Dp) {
    Large(height = 48.dp, horizontalPadding = 24.dp, iconSize = 20.dp),
    Medium(height = 40.dp, horizontalPadding = 16.dp, iconSize = 18.dp),
}

/** One photo of an album, as the grid draws it. */
@Immutable
class AlbumTile(
    /** What the image loader decodes; null while there is nothing to decode yet. */
    val model: Any?,
    /** Transfer progress while it runs, null otherwise. */
    val progress: Float?,
    /** Its send failed: the tile says so, and the bubble offers the retry. */
    val failed: Boolean,
)
