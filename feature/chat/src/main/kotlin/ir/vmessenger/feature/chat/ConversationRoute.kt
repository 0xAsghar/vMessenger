package ir.vmessenger.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.LocalAppObscured
import ir.vmessenger.core.designsystem.component.AttachmentSheet
import ir.vmessenger.core.designsystem.component.Composer
import ir.vmessenger.core.designsystem.component.ComposerState
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.ReplyPreview
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.gesture.swipeToGoBack
import ir.vmessenger.feature.chat.voice.ComposerMicButton
import ir.vmessenger.feature.chat.voice.RecordingRow
import kotlinx.coroutines.launch

private const val JUMP_VISIBLE_FROM_INDEX = 4
private const val AUTOSCROLL_MAX_INDEX = 2
private const val LOAD_EARLIER_MARGIN = 10

/**
 * A single conversation: header, warning banners, the message list and the composer.
 *
 * The screen owns the whole window (it is an outer-graph destination), so the design
 * system's [Composer] is the only owner of the bottom and IME insets — nothing here pads
 * around it.
 */
@Composable
@Suppress("LongParameterList") // one lambda per destination this screen can reach
fun ConversationRoute(
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val host = rememberConversationHost(viewModel, onOpenImage)

    ConversationEffects(viewModel = viewModel, state = state, host = host)
    BackHandler(enabled = state.composer.replyTo != null) { viewModel.onClearReply() }

    ConversationScreen(
        state = state,
        host = host,
        viewModel = viewModel,
        navigation = remember(onBack, onOpenContact, onOpenGroup) {
            ConversationNavigation(onBack, onOpenContact, onOpenGroup)
        },
        modifier = modifier,
    )
    ConversationSheets(state = state, host = host, viewModel = viewModel)
}

@Composable
private fun ConversationScreen(
    state: ConversationUiState,
    host: ConversationHost,
    viewModel: ConversationViewModel,
    navigation: ConversationNavigation,
    modifier: Modifier = Modifier,
) {
    val jumpVisible by remember {
        derivedStateOf { host.listState.firstVisibleItemIndex > JUMP_VISIBLE_FROM_INDEX }
    }
    VMessengerScaffold(
        title = state.header.title.ifBlank { stringResource(R.string.feature_chat_conversation) },
        onNavigateBack = navigation.onBack,
        floatingActionButton = {
            JumpToBottomFab(visible = jumpVisible) {
                host.scope.launch { host.listState.animateScrollToItem(0) }
            }
        },
        bottomBar = { ConversationComposer(state = state, host = host, viewModel = viewModel) },
        modifier = modifier,
        titleContent = { ConversationTitle(header = state.header, navigation = navigation) },
        snackbarHost = { VmSnackbarHost(host.snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // On the content, not the scaffold: a horizontal drag that starts in the composer
                // belongs to the composer, where the mic's slide-to-cancel lives.
                .swipeToGoBack(),
        ) {
            ConversationBanners(header = state.header, onOpenContact = navigation.onOpenContact)
            if (state.isEmpty) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    title = stringResource(R.string.feature_chat_conversation_empty_title),
                    body = stringResource(R.string.feature_chat_conversation_empty_body),
                    modifier = Modifier.weight(1f),
                )
            } else {
                ConversationMessageList(
                    state = state,
                    listState = host.listState,
                    actions = host.actions,
                    images = host.images,
                    voice = host.voice,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The composer, or the recording bar in its place: while a voice message is being recorded
 * there is nothing to type into, and the bar owns the same bottom insets the composer does.
 */
@Composable
private fun ConversationComposer(
    state: ConversationUiState,
    host: ConversationHost,
    viewModel: ConversationViewModel,
) {
    val recorder by viewModel.voice.recorderState.collectAsStateWithLifecycle()
    // Editing borrows the reply strip rather than adding a second banner: it is the same shape of
    // thing — a quoted line above the field with one way out — and the X cancels the edit.
    val editingLabel = stringResource(R.string.feature_chat_editing)
    val strip = when {
        state.composer.editingMessageId != null ->
            ReplyPreview(
                messageId = state.composer.editingMessageId,
                senderName = editingLabel,
                preview = state.composer.text,
            )
        else -> state.composer.replyTo?.let { rememberReplyPreview(it, state.header.title) }
    }
    Composer(
        state = ComposerState(text = state.composer.text, enabled = state.composer.enabled),
        onTextChange = viewModel::onTextChange,
        onSend = viewModel::onSend,
        onAttach = { host.sheets.attachOpen.value = true },
        replyTo = strip,
        onClearReply = {
            if (state.composer.editingMessageId != null) viewModel.onCancelEdit() else viewModel.onClearReply()
        },
        micButton = { ComposerMicButton(actions = host.mic.actions, enabled = state.composer.enabled) },
        // Same bar, different contents: the mic must not leave composition mid-gesture.
        recordingContent = if (!recorder.recording) {
            null
        } else {
            {
                RecordingRow(
                    state = recorder,
                    locked = host.mic.locked.value,
                    slide = host.mic.slide.value,
                    onCancel = host.mic.onCancel,
                )
            }
        },
    )
}

@Composable
private fun ConversationSheets(
    state: ConversationUiState,
    host: ConversationHost,
    viewModel: ConversationViewModel,
) {
    if (host.sheets.attachOpen.value) {
        val close = { host.sheets.attachOpen.value = false }
        AttachmentSheet(
            onPickPhoto = {
                close()
                host.sheets.picker.pickPhoto()
            },
            onPickVideo = {
                close()
                host.sheets.picker.pickVideo()
            },
            onPickFile = {
                close()
                host.sheets.picker.pickFile()
            },
            onDismiss = close,
        )
    }
    host.sheets.actionTarget.value?.let { messageId ->
        MessageActionsSheet(
            preview = state.textOf(messageId),
            abilities = state.abilitiesFor(messageId),
            actions = MessageSheetActions(
                onReply = { viewModel.onReply(messageId) },
                onEdit = { viewModel.onEditMessage(messageId) },
                onCopy = { host.sheets.onCopy(state, messageId) },
                onInfo = { viewModel.onShowInfo(messageId) },
                onDelete = { forEveryone ->
                    if (forEveryone) viewModel.onDeleteForEveryone(messageId) else viewModel.onDeleteMessage(messageId)
                },
                onDismiss = { host.sheets.actionTarget.value = null },
            ),
        )
    }
    val info by viewModel.deliveryInfo.collectAsStateWithLifecycle()
    info?.let { MessageInfoSheet(info = it, onDismiss = viewModel::onDismissInfo) }
}

/**
 * Paging, auto-scroll, jump-to-quoted and the read marker.
 *
 * `nearTop` is the reverse-layout equivalent of "scrolled to the beginning": increasing
 * indices go upwards, so the last visible item is the oldest one on screen.
 */
@Composable
private fun ConversationEffects(
    viewModel: ConversationViewModel,
    state: ConversationUiState,
    host: ConversationHost,
) {
    val scrollTarget by viewModel.scrollToMessageId.collectAsStateWithLifecycle()
    val newestId = (state.items.firstOrNull() as? ChatItem.Message)?.messageId
    val nearTop by remember {
        derivedStateOf {
            val info = host.listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_EARLIER_MARGIN
        }
    }

    LaunchedEffect(nearTop) { if (nearTop) viewModel.onLoadEarlier() }
    LaunchedEffect(newestId) {
        if (newestId != null && host.listState.firstVisibleItemIndex <= AUTOSCROLL_MAX_INDEX) {
            host.listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(scrollTarget, state.items) {
        val index = scrollTarget?.let { target -> state.items.indexOfFirst { it.key == target } } ?: -1
        if (index >= 0) {
            host.listState.animateScrollToItem(index)
            viewModel.onScrollHandled()
        }
    }

    // Attach and detach follow the *lifecycle*, and nothing else. This used to be keyed on
    // newestId so a message arriving with the chat open was read immediately — but a key change
    // runs the dispose half first, and onHidden() detaches the voice session, which cancels an
    // in-progress recording and deletes its file. Holding the mic while the other person sent
    // anything destroyed the voice message, silently, as if the press had ended. Re-reading is
    // its own effect below.
    //
    // Keyed on [LocalAppObscured]: this screen stays composed while the app is covered but not
    // yet locked, and "visible" there would mean marking the conversation read, telling the
    // notifier to stay quiet and
    // sending the peer a read receipt for messages nobody has been shown. A read receipt cannot
    // be retracted, and the unread state it clears is not recoverable either.
    val obscured = LocalAppObscured.current
    LifecycleResumeEffect(obscured) {
        if (!obscured) viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }

    // A message arriving with the chat open is read now rather than on the next visit. Separate
    // from the effect above so that it cannot detach anything: this only ever marks.
    LaunchedEffect(newestId, obscured) {
        if (!obscured) viewModel.onVisible()
    }
}
