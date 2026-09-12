package ir.vmessenger.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * `ChatMessage.lastError` is a stable code written by the outbox dispatcher, never display
 * text. This is the one place that turns it into a Persian sentence; an unknown code falls
 * back to the generic failure rather than leaking the code into the UI.
 */
@Composable
internal fun sendErrorText(code: String?): String = stringResource(
    when (code) {
        "peer_protocol_outdated" -> R.string.feature_chat_error_peer_protocol_outdated
        "peer_key_changed" -> R.string.feature_chat_error_peer_key_changed
        "endpoint_not_found" -> R.string.feature_chat_error_endpoint_not_found
        "network_unavailable" -> R.string.feature_chat_error_network_unavailable
        "mailbox_handoff" -> R.string.feature_chat_error_mailbox_handoff
        "contact_missing" -> R.string.feature_chat_error_contact_missing
        "contact_blocked" -> R.string.feature_chat_error_contact_blocked
        "contact_not_approved" -> R.string.feature_chat_error_contact_not_approved
        else -> R.string.feature_chat_error_send_failed
    },
)
