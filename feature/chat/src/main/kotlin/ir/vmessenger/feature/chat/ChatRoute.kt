package ir.vmessenger.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDirection

@Composable
fun ChatRoute(
    onOpenConversation: (String) -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()

    VMessengerScaffold(
        title = stringResource(R.string.feature_chat_title),
    ) { padding ->
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.feature_chat_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(conversations, key = { it.id }) { conversation ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        onClick = { onOpenConversation(conversation.id) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = conversation.contactName, style = MaterialTheme.typography.titleMedium)
                            conversation.lastMessagePreview?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationRoute(
    onBack: () -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val contactName by viewModel.contactName.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // reverseLayout anchors content to the bottom (messenger convention): with
    // the list reversed, index 0 is the newest message at the very bottom.
    val reversedMessages = remember(messages) { messages.asReversed() }

    LaunchedEffect(messages.lastOrNull()?.messageId) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    VMessengerScaffold(
        title = contactName ?: stringResource(R.string.feature_chat_conversation),
        onNavigateBack = onBack,
        bottomBar = {
            MessageComposer(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            items(reversedMessages, key = { it.messageId }) { message ->
                MessageBubble(message = message)
            }
        }
    }
}

@Composable
private fun MessageComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = stringResource(R.string.feature_chat_hint)) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            TextButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.feature_chat_send))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val alignment = if (isOutgoing) {
        Alignment.CenterStart
    } else {
        Alignment.CenterEnd
    }
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onSecondary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val statusColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = alignment,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = shape,
            color = bubbleColor,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                )
                // Delivery status is only meaningful for the sender's own messages.
                if (isOutgoing) {
                    Text(
                        text = statusLabel(message.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: DeliveryStatus): String = when (status) {
    DeliveryStatus.QUEUED -> stringResource(R.string.feature_chat_status_queued)
    DeliveryStatus.SENT -> stringResource(R.string.feature_chat_status_sent)
    DeliveryStatus.DELIVERED -> stringResource(R.string.feature_chat_status_delivered)
    DeliveryStatus.READ -> stringResource(R.string.feature_chat_status_read)
    DeliveryStatus.FAILED -> stringResource(R.string.feature_chat_status_failed)
}
