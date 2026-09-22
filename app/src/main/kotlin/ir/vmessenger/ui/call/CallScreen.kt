package ir.vmessenger.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import ir.vmessenger.R
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.data.call.CallSession
import ir.vmessenger.data.call.CallState

/**
 * The call screen: who, what state, and the two or three things a user can do about it.
 *
 * The answer button appears in exactly one state, [CallState.IncomingRinging], and nothing else on
 * this screen can open the microphone. Mute is offered only once audio is actually flowing, because
 * a mute control on a call that is still connecting promises something it cannot yet deliver.
 */
@Composable
fun CallScreen(
    session: CallSession,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(VmSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(VmSpacing.xxl))
        CallHeader(session)
        CallControls(session = session, actions = actions)
    }
}

@Composable
private fun CallHeader(session: CallSession) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = session.peerName,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(VmSpacing.sm))
        Text(
            text = stringResource(statusTextOf(session)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CallControls(session: CallSession, actions: CallActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (session.state.microphoneOpen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
            ) {
                VmOutlinedButton(
                    text = stringResource(if (session.muted) R.string.call_unmute else R.string.call_mute),
                    onClick = { actions.onToggleMute(!session.muted) },
                    modifier = Modifier.weight(1f),
                )
                VmOutlinedButton(
                    text = stringResource(
                        if (session.speakerOn) R.string.call_earpiece else R.string.call_speaker,
                    ),
                    onClick = { actions.onToggleSpeaker(!session.speakerOn) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(VmSpacing.md))
        }
        if (session.state == CallState.IncomingRinging) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
            ) {
                VmOutlinedButton(
                    text = stringResource(R.string.call_decline),
                    onClick = actions.onDecline,
                    modifier = Modifier.weight(1f),
                )
                VmButton(
                    text = stringResource(R.string.call_accept),
                    onClick = actions.onAccept,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            VmButton(
                text = stringResource(R.string.call_hang_up),
                onClick = actions.onHangUp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Says what is true, including that a ringing phone has not yet been reached. */
private fun statusTextOf(session: CallSession): Int = when (session.state) {
    CallState.OutgoingRinging -> if (session.peerAlerting) R.string.call_ringing else R.string.call_calling
    CallState.IncomingRinging -> R.string.call_incoming
    CallState.Connecting -> R.string.call_connecting
    CallState.Active -> R.string.call_active
    CallState.Reconnecting -> R.string.call_reconnecting
    CallState.Idle, CallState.Ending -> R.string.call_ended
}
