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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.vmessenger.R
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.data.call.CallSession
import ir.vmessenger.data.call.CallState
import kotlinx.coroutines.delay

private val AvatarSize = 120.dp
private const val MILLIS_PER_SECOND = 1_000L

/**
 * The call screen: who, where the call is, and the few things a user can do about it.
 *
 * The answer button appears in exactly one state, [CallState.IncomingRinging], and nothing else on
 * this screen can open the microphone. Mute is offered only once audio is actually flowing, because
 * a mute control on a call that is still connecting promises something it cannot yet deliver.
 *
 * Round buttons, as every phone draws them: ending is red wherever it appears, answering is the
 * accent, and the two toggles fill in while they are on.
 */
@Composable
fun CallScreen(
    session: CallSession,
    avatarSeed: ByteArray,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = VmSpacing.xl, vertical = VmSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        CallHeader(session = session, avatarSeed = avatarSeed)
        Spacer(Modifier.weight(1.5f))
        CallControls(session = session, actions = actions)
    }
}

@Composable
private fun CallHeader(session: CallSession, avatarSeed: ByteArray) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(seed = avatarSeed, name = session.peerName, size = AvatarSize)
        Spacer(Modifier.height(VmSpacing.lg))
        VmText(
            text = session.peerName,
            style = VmTheme.typography.headingLg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(VmSpacing.xs))
        VmText(
            text = statusOf(session),
            style = VmTheme.typography.bodyLg,
            color = VmTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Where the call is; once it is live, how long it has been — the one number worth watching then. */
@Composable
private fun statusOf(session: CallSession): String {
    val elapsed = elapsedSince(session.connectedAtMs.takeIf { session.state == CallState.Active })
    return if (elapsed != null) VmTextFormat.duration(elapsed) else stringResource(statusTextOf(session))
}

/** Milliseconds since [since], ticking on the second; null while there is nothing to count. */
@Composable
private fun elapsedSince(since: Long?): Long? {
    val elapsed by produceState<Long?>(initialValue = null, since) {
        if (since == null) {
            value = null
            return@produceState
        }
        while (true) {
            val now = System.currentTimeMillis() - since
            value = now
            delay(MILLIS_PER_SECOND - now.mod(MILLIS_PER_SECOND))
        }
    }
    return elapsed
}

@Composable
private fun CallControls(session: CallSession, actions: CallActions) {
    if (session.state == CallState.IncomingRinging) {
        RingingControls(actions)
    } else {
        LiveControls(session, actions)
    }
}

@Composable
private fun RingingControls(actions: CallActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        RoundCallButton(
            icon = Icons.Filled.CallEnd,
            label = stringResource(R.string.call_decline),
            container = VmTheme.colors.bgCritical,
            content = Color.White,
            onClick = actions.onDecline,
        )
        RoundCallButton(
            icon = Icons.Filled.Call,
            label = stringResource(R.string.call_accept),
            container = VmTheme.colors.bgAccent,
            content = VmTheme.colors.textOnSolid,
            onClick = actions.onAccept,
        )
    }
}

@Composable
private fun LiveControls(session: CallSession, actions: CallActions) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xl),
    ) {
        if (session.state.microphoneOpen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToggleCallButton(
                    icon = Icons.Filled.MicOff,
                    label = stringResource(R.string.call_mute_short),
                    description = stringResource(R.string.call_mute),
                    checked = session.muted,
                    onCheckedChange = actions.onToggleMute,
                )
                ToggleCallButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = stringResource(R.string.call_speaker),
                    description = stringResource(R.string.call_speaker),
                    checked = session.speakerOn,
                    onCheckedChange = actions.onToggleSpeaker,
                )
            }
        }
        RoundCallButton(
            icon = Icons.Filled.CallEnd,
            label = stringResource(R.string.call_hang_up),
            container = VmTheme.colors.bgCritical,
            content = Color.White,
            onClick = actions.onHangUp,
        )
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
