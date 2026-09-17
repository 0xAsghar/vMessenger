package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowUpward
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
 * Bottom bar of a conversation: a send/mic button that morphs with the draft, a growing text
 * field, and the attach button. Window insets are handled here, so callers pass it straight to
 * `Scaffold(bottomBar = ...)`.
 *
 * Reading order is layout-relative, so under the app's RTL locale the send/mic button sits at the
 * **right** edge and attach at the left.
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
    // Insets first, visual margin second, and both on the outermost node: three separate comments
    // (here, ConversationRoute and ChatGraph) pin this composable as the single owner of the
    // conversation screen's bottom and IME inset. A margin applied before them, or by the caller,
    // would double-pad or let the card slide under the navigation bar.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xs),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            // shadowElevation, not tonalElevation: VmElevation documents itself as shadow values,
            // with tonal handled by the surfaceContainer* roles. This was its one contradiction.
            shadowElevation = VmElevation.sheet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val recording = recordingContent != null
            Column(modifier = Modifier.padding(VmSpacing.sm)) {
                if (!recording) ReplyStrip(replyTo = replyTo, onClearReply = onClearReply)
                Row(
                    verticalAlignment = if (recording) Alignment.CenterVertically else Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs),
                ) {
                    // ONE call site, outside the if/else below, and leading in both modes.
                    // Composable identity is positional: duplicating this into each branch would
                    // tear the mic down as recordingContent appears, the pointer loop would never
                    // see the release, and the recording could never be ended. The slot around it
                    // is unconditional for the same reason.
                    FieldHeightSlot {
                        if (state.canSend && !recording) SendButton(onSend) else micButton()
                    }
                    if (recordingContent != null) {
                        recordingContent()
                    } else {
                        ComposerField(
                            state = state,
                            onTextChange = onTextChange,
                            modifier = Modifier.weight(1f),
                        )
                        FieldHeightSlot {
                            IconButton(onClick = onAttach, enabled = state.enabled) {
                                Icon(
                                    imageVector = Icons.Outlined.AttachFile,
                                    contentDescription = stringResource(R.string.vm_composer_attach),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Holds a composer button in a box as tall as a one-line field, centred.
 *
 * The row aligns to the bottom so the buttons stay by the last line of a long draft. On its own
 * that put a 48dp button on the bottom edge of the 56dp field, visibly low against a single line;
 * centred in a field-high box it is level with one line, and still sits at the bottom of several.
 */
@Composable
private fun FieldHeightSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.heightIn(min = TextFieldDefaults.MinHeight),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
            imageVector = Icons.Filled.ArrowUpward,
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
