package ir.vmessenger.feature.contacts

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmListRow
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.ContactRelationshipStatus

/**
 * Requests first — they are the only rows that expire if ignored — then contacts by name.
 *
 * The contact row is built here rather than from `ChatListItem` because it needs a trailing
 * cluster (status, key-change shield) that the chat row has no slot for; the metrics and tokens
 * are the same, so the two lists still line up.
 */
@Composable
internal fun ContactsList(
    state: ContactsUiState,
    listState: LazyListState,
    callbacks: ContactsListCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        if (state.requests.isNotEmpty()) {
            item(key = "requests_header") {
                SectionHeader(
                    title = stringResource(
                        R.string.contacts_requests_title,
                        VmTextFormat.digits(state.requests.size.toString()),
                    ),
                )
            }
            items(state.requests, key = { "request_${it.requestId}" }) { request ->
                RequestRowItem(
                    request = request,
                    onApprove = { callbacks.onApproveRequest(request.requestId) },
                    onReject = { callbacks.onRejectRequest(request.requestId) },
                )
            }
        }
        if (state.contacts.isNotEmpty() && state.requests.isNotEmpty()) {
            item(key = "contacts_header") {
                SectionHeader(title = stringResource(R.string.contacts_section_contacts))
            }
        }
        items(state.contacts, key = { it.id }) { contact ->
            ContactRowItem(
                contact = contact,
                onClick = { callbacks.onOpenContact(contact.id) },
                onLongClick = { callbacks.onLongPressContact(contact.id) },
                onChat = { callbacks.onChat(contact.id) },
                onCall = { callbacks.onCall(contact.id) },
            )
        }
    }
}

@Composable
private fun ContactRowItem(
    contact: ContactRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onChat: () -> Unit,
    onCall: () -> Unit,
) {
    val subtitle = contactSubtitle(contact)
    VmListRow(
        title = contact.name,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        subtitle = if (subtitle != null || contact.sharesLocation) {
            { ContactSubtitle(text = subtitle, sharesLocation = contact.sharesLocation) }
        } else {
            null
        },
        trailing = { ContactRowTrailing(contact = contact, onChat = onChat, onCall = onCall) },
        avatar = { Avatar(seed = contact.identityHash, name = contact.name) },
    )
}

/** Status marks, then — for someone we can talk to — one tap to chat and one to call. */
@Composable
private fun ContactRowTrailing(contact: ContactRow, onChat: () -> Unit, onCall: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(VmSpacing.xs),
        ) {
            if (contact.keyChangePending) {
                KeyChangeShield()
            }
            if (contact.status != ContactRelationshipStatus.APPROVED) {
                ContactStatusChip(status = contact.status)
            }
        }
        if (contact.canChat) {
            VmIconButton(
                icon = Icons.Outlined.ChatBubbleOutline,
                contentDescription = stringResource(R.string.contacts_action_chat, contact.name),
                onClick = onChat,
                tint = VmTheme.colors.iconSecondary,
            )
            VmIconButton(
                icon = Icons.Outlined.Call,
                contentDescription = stringResource(R.string.contacts_action_call, contact.name),
                onClick = onCall,
                tint = VmTheme.colors.iconSecondary,
            )
        }
    }
}

@Composable
private fun RequestRowItem(
    request: ContactRequestRow,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = VmSizes.listItemHeight)
            .padding(start = VmSpacing.lg, end = VmSpacing.sm, top = VmSpacing.sm, bottom = VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Avatar(seed = request.identityHash, name = request.name)
        Column(modifier = Modifier.weight(1f)) {
            VmText(
                text = request.name,
                style = VmTheme.typography.bodyLgMedium,
                color = VmTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UserHashText(
                text = request.userHash,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VmTextButton(text = stringResource(R.string.contacts_request_approve), onClick = onApprove)
        VmTextButton(text = stringResource(R.string.contacts_request_reject), onClick = onReject, destructive = true)
    }
}
