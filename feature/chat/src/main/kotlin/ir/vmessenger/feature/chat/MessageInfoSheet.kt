package ir.vmessenger.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.DeliveryTicks
import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.VmBottomSheet
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDeliveryInfo
import ir.vmessenger.domain.model.RecipientDelivery
import ir.vmessenger.feature.chat.group.hexToBytes

/**
 * Everything the app can honestly say about one message.
 *
 * The message-level block is what a 1:1 or an incoming message has to show: per-recipient rows
 * exist only for what we sent, so without it the sheet was empty everywhere except an outgoing
 * group message, which is why it used to be gated to exactly that.
 *
 * A group message is N pairwise sends, so the one tick on the bubble is an aggregate — it turns
 * to "delivered" only once everybody has it. The rows below are where that collapses back into
 * the truth: one per member, with the moment they received and read it.
 *
 * What is missing is missing on purpose. The route a message took, how many attempts it needed
 * and its size on the wire are not persisted anywhere, and a plausible-looking guess at any of
 * them would be worse than their absence.
 */
@Composable
internal fun MessageInfoSheet(
    info: MessageDeliveryInfo,
    onDismiss: () -> Unit,
) {
    VmBottomSheet(title = stringResource(R.string.feature_chat_message_info), onDismiss = onDismiss) {
        MessageFacts(info)
        if (info.recipients.isNotEmpty()) {
            SectionHeader(title = stringResource(R.string.feature_chat_info_recipients))
            for (recipient in info.recipients) {
                RecipientRow(recipient)
            }
        }
    }
}

/**
 * The message-level timeline, dropping every step that has not happened yet.
 *
 * There is no "written" row. It was the moment the row was created on this device — for a message
 * we received, the moment it arrived — which read as a second, slightly different "sent" and said
 * nothing the other rows do not. An edit and a delete-for-everyone, which do change what the
 * bubble shows, get rows of their own.
 */
@Composable
private fun MessageFacts(info: MessageDeliveryInfo) {
    // "Sent" means our transport wrote the frame for a message we sent, and the sender's own
    // clock for one we received — two different things that must not share a label.
    val sentLabel = if (info.outgoing) R.string.feature_chat_info_sent_at else R.string.feature_chat_info_sender_time
    val facts = buildList {
        info.sentAtUnixMs?.let { add(stringResource(sentLabel) to VmDateFormat.dayAndTime(it)) }
        info.deliveredAtUnixMs?.let {
            add(stringResource(R.string.feature_chat_info_delivered_at) to VmDateFormat.dayAndTime(it))
        }
        info.readAtUnixMs?.let {
            add(stringResource(R.string.feature_chat_info_read_at) to VmDateFormat.dayAndTime(it))
        }
        info.editedAtUnixMs?.let {
            add(stringResource(R.string.feature_chat_info_edited_at) to VmDateFormat.dayAndTime(it))
        }
        info.deletedAtUnixMs?.let {
            add(stringResource(R.string.feature_chat_info_deleted_at) to VmDateFormat.dayAndTime(it))
        }
        info.sizeBytes?.let { add(stringResource(R.string.feature_chat_info_size) to VmTextFormat.fileSize(it)) }
    }
    for ((label, value) in facts) {
        SettingsRow(label = label, trailing = SettingsTrailing.Text(value))
    }
}

@Composable
private fun RecipientRow(recipient: RecipientDelivery) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = VmSizes.touchTarget)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
    ) {
        Avatar(
            seed = hexToBytes(recipient.identityHash),
            name = recipient.displayName,
            size = VmSizes.avatarSm,
        )
        Column(modifier = Modifier.weight(1f)) {
            VmText(text = recipient.displayName, style = VmTheme.typography.bodyLg)
            VmText(
                text = timestamps(recipient),
                style = VmTheme.typography.bodySm,
                color = VmTheme.colors.textSecondary,
            )
        }
        recipient.status.toRecipientTicks()?.let { DeliveryTicks(state = it) }
    }
}

/**
 * "Delivered 10:04 · Read 10:07", dropping whichever half has not happened. A recipient with
 * neither reads as their status alone, which is the honest answer for one still queued.
 */
@Composable
private fun timestamps(recipient: RecipientDelivery): String {
    val parts = buildList {
        recipient.sentAtUnixMs?.let {
            add(stringResource(R.string.feature_chat_info_sent_short, VmDateFormat.time(it)))
        }
        recipient.deliveredAtUnixMs?.let {
            add(stringResource(R.string.feature_chat_info_delivered, VmDateFormat.time(it)))
        }
        recipient.readAtUnixMs?.let {
            add(stringResource(R.string.feature_chat_info_read, VmDateFormat.time(it)))
        }
    }
    return parts.joinToString(SEPARATOR).ifEmpty { stringResource(statusLabel(recipient.status)) }
}

private fun statusLabel(status: DeliveryStatus): Int = when (status) {
    DeliveryStatus.FAILED -> R.string.feature_chat_info_failed
    DeliveryStatus.SENT -> R.string.feature_chat_info_sent
    else -> R.string.feature_chat_info_pending
}

/** Ticks for a single recipient; a queued or failed one has none to show. */
private fun DeliveryStatus.toRecipientTicks(): DeliveryTicksState? = when (this) {
    DeliveryStatus.SENT -> DeliveryTicksState.SENT
    DeliveryStatus.DELIVERED -> DeliveryTicksState.DELIVERED
    DeliveryStatus.READ -> DeliveryTicksState.READ
    DeliveryStatus.QUEUED, DeliveryStatus.FAILED -> null
}

private const val SEPARATOR = " · "
