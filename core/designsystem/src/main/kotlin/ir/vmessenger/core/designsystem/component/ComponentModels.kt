package ir.vmessenger.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import ir.vmessenger.core.designsystem.theme.vm

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
        val vm = MaterialTheme.vm
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
)
