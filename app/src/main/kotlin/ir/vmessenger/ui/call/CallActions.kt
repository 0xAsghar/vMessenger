package ir.vmessenger.ui.call

import androidx.compose.runtime.Stable

/**
 * What the call screen can do, in one holder so [CallScreen] keeps a readable signature.
 *
 * Every one of these is a call into the coordinator's state machine; none of them is a shortcut
 * around it. There is deliberately nothing here that opens audio directly.
 */
@Stable
class CallActions(
    val onAccept: () -> Unit,
    val onDecline: () -> Unit,
    val onHangUp: () -> Unit,
    val onToggleMute: (Boolean) -> Unit,
)
