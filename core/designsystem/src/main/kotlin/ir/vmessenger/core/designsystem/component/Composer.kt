package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * Bottom bar of a conversation, in Element X's arrangement: attach at the start, a rounded field
 * that grows with the draft, and at the end the button that sends — an accent circle once there is
 * something to send, the mic until then. Window insets are handled here, so callers pass it
 * straight to the scaffold's bottom bar.
 *
 * Reading order is layout-relative: send sits at the right in English and at the left in Persian,
 * where every messenger in either language puts it.
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
    val c = VmTheme.colors
    // The canvas first, so it runs under the navigation bar; insets next; visual margin last. This
    // composable is the single owner of the conversation screen's bottom and keyboard inset — a
    // caller that padded for either would double it.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bgCanvas)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        VmDivider()
        val recording = recordingContent != null
        Column(modifier = Modifier.padding(horizontal = VmSpacing.xs, vertical = VmSpacing.xs)) {
            if (!recording) ReplyStrip(replyTo = replyTo, onClearReply = onClearReply)
            Row(
                verticalAlignment = if (recording) Alignment.CenterVertically else Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(VmSpacing.xxs),
            ) {
                if (recordingContent != null) {
                    recordingContent()
                } else {
                    FieldHeightSlot {
                        VmIconButton(
                            icon = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.vm_composer_attach),
                            onClick = onAttach,
                            enabled = state.enabled,
                            tint = c.iconSecondary,
                        )
                    }
                    ComposerField(
                        state = state,
                        onTextChange = onTextChange,
                        modifier = Modifier.weight(1f),
                    )
                }
                // ONE call site, after the if/else above and trailing in both modes. Composable
                // identity is positional: duplicating this into each branch would tear the mic
                // down as recordingContent appears, the pointer loop would never see the release,
                // and the recording could never be ended.
                FieldHeightSlot {
                    if (state.canSend && !recording) SendButton(onSend) else micButton()
                }
            }
        }
    }
}

/**
 * Holds a composer button in a box as tall as a one-line field, centred, so it is level with a
 * single line and still sits by the last line of a long draft.
 */
@Composable
private fun FieldHeightSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.heightIn(min = FIELD_MIN_HEIGHT),
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
    val c = VmTheme.colors
    val enabled = state.enabled && !state.recording
    val style = VmTheme.typography.bodyLg.merge(TextStyle(color = if (enabled) c.textPrimary else c.textDisabled))
    BasicTextField(
        value = state.text,
        onValueChange = onTextChange,
        enabled = enabled,
        textStyle = style,
        maxLines = MAX_LINES,
        cursorBrush = SolidColor(c.textPrimary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier
            .padding(vertical = VmSpacing.xxs)
            .heightIn(min = FIELD_MIN_HEIGHT - VmSpacing.xs, max = FIELD_MAX_HEIGHT),
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .background(c.bgSubtle, FieldShape)
                    .padding(horizontal = VmSpacing.lg, vertical = FIELD_TEXT_VERTICAL),
            ) {
                if (state.text.isEmpty()) {
                    VmText(
                        text = stringResource(R.string.vm_composer_placeholder),
                        style = style,
                        color = c.textPlaceholder,
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun ReplyStrip(replyTo: ReplyPreview?, onClearReply: () -> Unit) {
    if (replyTo == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = VmSpacing.md, top = VmSpacing.xs),
    ) {
        ReplyQuote(
            senderName = replyTo.senderName,
            preview = replyTo.preview,
            modifier = Modifier.weight(1f),
        )
        VmIconButton(
            icon = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.vm_composer_clear_reply),
            onClick = onClearReply,
            tint = VmTheme.colors.iconSecondary,
        )
    }
}

/** The one filled thing in the bar: an accent circle, so "send" is found without looking for it. */
@Composable
private fun SendButton(onSend: () -> Unit) {
    val c = VmTheme.colors
    VmSurface(
        onClick = onSend,
        shape = CircleShape,
        color = c.bgAccent,
        contentColor = c.textOnSolid,
        modifier = Modifier
            .padding((VmSizes.touchTarget - SEND_SIZE) / 2)
            .size(SEND_SIZE),
    ) {
        Box(contentAlignment = Alignment.Center) {
            VmIcon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = stringResource(R.string.vm_composer_send),
                size = SEND_ICON,
            )
        }
    }
}

/** What the mic slot draws when a caller has no recorder: the affordance, without the gesture. */
@Composable
private fun InertMicButton() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(VmSizes.touchTarget)) {
        VmIcon(
            imageVector = Icons.Filled.Mic,
            contentDescription = stringResource(R.string.vm_composer_record),
            tint = VmTheme.colors.iconSecondary,
        )
    }
}

private const val MAX_LINES = 6
private val FIELD_MIN_HEIGHT = 48.dp
private val FIELD_MAX_HEIGHT = 160.dp
private val FIELD_TEXT_VERTICAL = 9.dp
private val FieldShape = RoundedCornerShape(22.dp)
private val SEND_SIZE = 40.dp
private val SEND_ICON = 20.dp
