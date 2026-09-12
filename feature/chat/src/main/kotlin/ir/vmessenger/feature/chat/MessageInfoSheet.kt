package ir.vmessenger.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.DeliveryTicks
import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.RecipientDelivery
import ir.vmessenger.feature.chat.group.hexToBytes
import kotlinx.collections.immutable.ImmutableList

/**
 * Who actually has this message.
 *
 * A group message is N pairwise sends, so the one tick on the bubble is an aggregate — it turns
 * to "delivered" only once everybody has it. This sheet is where that collapses back into the
 * truth: one row per member, with the moment they received and read it.
 */
@Composable
internal fun MessageInfoSheet(
    recipients: ImmutableList<RecipientDelivery>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = VmSpacing.xl)) {
            Text(
                text = stringResource(R.string.feature_chat_message_info),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = VmSpacing.md, vertical = VmSpacing.sm),
            )
            for (recipient in recipients) {
                RecipientRow(recipient)
            }
        }
    }
}

@Composable
private fun RecipientRow(recipient: RecipientDelivery) {
    ListItem(
        headlineContent = { Text(text = recipient.displayName) },
        supportingContent = { Text(text = timestamps(recipient)) },
        leadingContent = {
            Avatar(
                seed = hexToBytes(recipient.identityHash),
                name = recipient.displayName,
                size = VmSizes.avatarSm,
            )
        },
        trailingContent = { recipient.status.toRecipientTicks()?.let { DeliveryTicks(state = it) } },
    )
}

/**
 * "Delivered 10:04 · Read 10:07", dropping whichever half has not happened. A recipient with
 * neither reads as their status alone, which is the honest answer for one still queued.
 */
@Composable
private fun timestamps(recipient: RecipientDelivery): String {
    val parts = buildList {
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
