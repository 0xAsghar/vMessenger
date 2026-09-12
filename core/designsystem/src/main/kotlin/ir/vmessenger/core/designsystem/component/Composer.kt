package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

private val FieldMaxHeight = 160.dp

/**
 * Bottom bar of a conversation: attach button, growing text field and a send/mic button that
 * morphs with the draft. Window insets are handled here, so callers pass it straight to
 * `Scaffold(bottomBar = ...)`.
 *
 * The mic is a slot: the recording state machine (hold, lock, slide-to-cancel) belongs to the
 * chat feature, and so does the gesture that drives it. The default is a plain, inert icon, for
 * callers with nothing to record.
 *
 * While a recording runs the caller passes [recordingContent], which replaces the attach button
 * and the text field. The bar itself is deliberately **not** replaced: the finger is still down
 * on the mic, and swapping in a different bar would take that button out of composition, so the
 * gesture could never report its release and the recording could never end.
 */
@Suppress("LongParameterList") // Compose slot API: one callback per independent composer action.
@Composable
fun Composer(
    state: ComposerState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
    replyTo: ReplyPreview? = null,
    onClearReply: () -> Unit = {},
    micButton: @Composable () -> Unit = { InertMicButton() },
    recordingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = VmElevation.bar,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.padding(VmSpacing.sm)) {
            if (recordingContent == null) ReplyStrip(replyTo = replyTo, onClearReply = onClearReply)
            Row(
                verticalAlignment = if (recordingContent == null) Alignment.Bottom else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs),
            ) {
                if (recordingContent != null) {
                    recordingContent()
                } else {
                    IconButton(onClick = onAttach, enabled = state.enabled) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = stringResource(R.string.vm_composer_attach),
                        )
                    }
                    ComposerField(
                        state = state,
                        onTextChange = onTextChange,
                        modifier = Modifier.weight(1f),
                    )
                }
                // The mic keeps this position in both modes, so its gesture survives the switch.
                if (state.canSend && recordingContent == null) SendButton(onSend) else micButton()
            }
        }
    }
}

@Composable
private fun ComposerField(
    state: ComposerState,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = state.text,
        onValueChange = onTextChange,
        enabled = state.enabled && !state.recording,
        placeholder = { Text(text = stringResource(R.string.vm_composer_placeholder)) },
        textStyle = MaterialTheme.typography.bodyLarge,
        maxLines = 6,
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.heightIn(max = FieldMaxHeight),
    )
}

@Composable
private fun ReplyStrip(replyTo: ReplyPreview?, onClearReply: () -> Unit) {
    if (replyTo == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VmSpacing.xs),
    ) {
        ReplyQuote(
            senderName = replyTo.senderName,
            preview = replyTo.preview,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClearReply) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.vm_composer_clear_reply),
            )
        }
    }
}

@Composable
private fun SendButton(onSend: () -> Unit) {
    IconButton(onClick = onSend, modifier = Modifier.size(VmSizes.touchTarget)) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = stringResource(R.string.vm_composer_send),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** What the mic slot draws when a caller has no recorder: the affordance, without the gesture. */
@Composable
private fun InertMicButton() {
    Icon(
        imageVector = Icons.Filled.Mic,
        contentDescription = stringResource(R.string.vm_composer_record),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(VmSizes.touchTarget)
            .padding(VmSpacing.md),
    )
}
