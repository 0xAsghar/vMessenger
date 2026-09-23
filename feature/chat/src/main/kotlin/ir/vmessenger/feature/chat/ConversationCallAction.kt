package ir.vmessenger.feature.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.VmIconButton

/**
 * The call button in the conversation's top bar.
 *
 * Absent rather than disabled on a group thread: calls are one-to-one, and a greyed-out button
 * suggests a feature that is merely switched off. It is also absent for a blocked contact — the
 * point of blocking someone is that you do not talk to them.
 */
@Composable
internal fun ConversationCallAction(header: ConversationHeaderUi, onStartCall: (String) -> Unit) {
    val contactId = header.contactId
    if (contactId == null || header.blocked) return
    VmIconButton(
        icon = Icons.Outlined.Call,
        contentDescription = stringResource(R.string.feature_chat_call_start),
        onClick = { onStartCall(contactId) },
    )
}
