package ir.vmessenger.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDirection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute(
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    var selectedConversationId by remember { mutableStateOf<String?>(null) }

    if (selectedConversationId != null) {
        ConversationRoute(
            conversationId = selectedConversationId!!,
            onBack = { selectedConversationId = null },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(text = stringResource(R.string.feature_chat_title)) })
            },
        ) { padding ->
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = stringResource(R.string.feature_chat_empty))
                }
            } else {
                LazyColumn(modifier = Modifier.padding(padding)) {
                    items(conversations, key = { it.id }) { conversation ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            onClick = { selectedConversationId = conversation.id },
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = conversation.contactName, style = MaterialTheme.typography.titleMedium)
                                conversation.lastMessagePreview?.let {
                                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationRoute(
    conversationId: String,
    onBack: () -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(conversationId) {
        viewModel.load(conversationId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.feature_chat_conversation)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(text = stringResource(R.string.feature_chat_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.messageId }) { message ->
                    MessageBubble(message = message)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(text = stringResource(R.string.feature_chat_hint)) },
                )
                TextButton(
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.feature_chat_send))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ir.vmessenger.domain.model.ChatMessage) {
    val alignment = if (message.direction == MessageDirection.OUTGOING) {
        Alignment.CenterEnd
    } else {
        Alignment.CenterStart
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(modifier = Modifier.padding(4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = message.text)
                Text(
                    text = statusLabel(message.status),
                    style = MaterialTheme.typography.labelSmall,
                )
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
