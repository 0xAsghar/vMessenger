package ir.vmessenger.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.VmSnackbarHostState
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.foundation.copyNeedsConfirmation
import ir.vmessenger.feature.chat.voice.MicButtonActions
import ir.vmessenger.feature.chat.voice.VoiceBubbleHost
import ir.vmessenger.feature.chat.voice.rememberRecordAudioPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val ANY_MIME = "*/*"

/** Per pick, like the other messengers' albums; the system picker enforces it in its own UI. */
private const val MAX_PICKED_MEDIA = 10

/** Where the conversation can navigate to; bundled to keep the screen's parameter list short. */
@Stable
internal class ConversationNavigation(
    val onBack: () -> Unit,
    val onOpenContact: (String) -> Unit,
    val onOpenGroup: (String) -> Unit,
    /** Places a voice call to this conversation's contact; never offered on a group thread. */
    val onStartCall: (String) -> Unit,
)

/** The three attachment sources, already bound to their activity-result launchers. */
@Stable
internal class AttachmentPicker(
    val pickPhoto: () -> Unit,
    val pickVideo: () -> Unit,
    val pickFile: () -> Unit,
)

/** The two transient sheets of the screen, plus what they act on. */
@Stable
internal class ConversationSheetState(
    val attachOpen: MutableState<Boolean>,
    val actionTarget: MutableState<String?>,
    val picker: AttachmentPicker,
    val onCopy: (ConversationUiState, String) -> Unit,
    val onShare: (ConversationUiState, String) -> Unit,
)

/**
 * Everything the conversation screen needs that is not ViewModel state: the list position,
 * the snackbar, the Coil request factory, the bubble callbacks and the sheets. Bundled so
 * each composable stays inside the parameter budget.
 */
@Stable
@Suppress("LongParameterList") // one field per screen-owned concern; a nested bag would only hide them
internal class ConversationHost(
    val listState: LazyListState,
    val snackbar: VmSnackbarHostState,
    val scope: CoroutineScope,
    val images: AttachmentImages,
    val actions: MessageActions,
    val sheets: ConversationSheetState,
    val voice: VoiceBubbleHost,
    val mic: MicHost,
)

/**
 * The mic gesture's own state: whether the recording is hands-free and how far the finger has
 * slid toward cancelling. It lives here rather than in the ViewModel because it is a property
 * of the gesture on screen, not of the conversation.
 */
@Stable
internal class MicHost(
    val actions: MicButtonActions,
    val locked: State<Boolean>,
    val slide: State<Float>,
    val onCancel: () -> Unit,
    val onSend: () -> Unit,
)

/**
 * Builds the host. The long-press target and the attachment sheet are `rememberSaveable`,
 * so a rotation with a sheet open comes back the way the user left it.
 */
@Composable
internal fun rememberConversationHost(
    viewModel: ConversationViewModel,
    onOpenImage: (String) -> Unit,
): ConversationHost {
    val listState = rememberLazyListState()
    val snackbar = rememberVmSnackbar()
    val scope = rememberCoroutineScope()
    val images = remember(viewModel) { AttachmentImages(viewModel::openAttachmentStream) }
    // One shared cell: a long press on a bubble is what opens the message-actions sheet.
    val actionTarget = rememberSaveable { mutableStateOf<String?>(null) }
    val actions = rememberMessageActions(viewModel, onOpenImage, actionTarget, snackbar, scope)
    val sheets = rememberSheetState(viewModel, actionTarget, snackbar, scope)
    val voice = rememberVoiceBubbleHost(viewModel)
    val mic = rememberMicHost(viewModel, snackbar)
    return remember(listState, actions, sheets, images, voice, mic) {
        ConversationHost(
            listState = listState,
            snackbar = snackbar,
            scope = scope,
            images = images,
            actions = actions,
            sheets = sheets,
            voice = voice,
            mic = mic,
        )
    }
}

@Composable
private fun rememberVoiceBubbleHost(viewModel: ConversationViewModel): VoiceBubbleHost {
    val playback by viewModel.voice.playbackState.collectAsStateWithLifecycle()
    return remember(playback, viewModel) {
        VoiceBubbleHost(
            playback = playback,
            onToggle = viewModel.voice::toggle,
            onSeek = viewModel.voice::seek,
            onToggleSpeed = viewModel.voice::toggleSpeed,
        )
    }
}

/**
 * The mic press, wired to the recorder. The permission check sits in [MicButtonActions.onStart]
 * so a press without permission asks for it and simply does not become a recording — nothing
 * starts and then silently fails.
 */
@Composable
private fun rememberMicHost(viewModel: ConversationViewModel, snackbar: VmSnackbarHostState): MicHost {
    val permission = rememberRecordAudioPermission(snackbar)
    val locked = remember { mutableStateOf(false) }
    val slide = remember { mutableFloatStateOf(0f) }
    val cancel = remember(viewModel) {
        {
            locked.value = false
            slide.floatValue = 0f
            viewModel.voice.cancelRecording()
        }
    }
    val send = remember(viewModel) {
        {
            locked.value = false
            slide.floatValue = 0f
            viewModel.voice.sendRecording()
        }
    }
    val actions = remember(viewModel, permission, cancel, send) {
        MicButtonActions(
            onStart = { permission.ensureGranted() && viewModel.voice.startRecording() },
            onCancel = cancel,
            onSend = send,
            onLocked = {
                locked.value = true
                // The recorder is told too: it decides whether backgrounding the app throws the
                // recording away, and a locked one must survive to the ON_STOP handler that sends
                // it. See VoiceSession.detach.
                viewModel.voice.markHandsFree()
            },
            onSlide = { slide.floatValue = it },
        )
    }
    return remember(actions) { MicHost(actions, locked, slide, cancel, send) }
}

@Composable
private fun rememberMessageActions(
    viewModel: ConversationViewModel,
    onOpenImage: (String) -> Unit,
    actionTarget: MutableState<String?>,
    snackbar: VmSnackbarHostState,
    scope: CoroutineScope,
): MessageActions {
    val context = LocalContext.current
    val openFailed = stringResource(R.string.feature_chat_attachment_open_failed)
    return remember(viewModel, onOpenImage) {
        MessageActions(
            onLongPress = { actionTarget.value = it },
            onReply = viewModel::onReply,
            onRetry = viewModel::onRetry,
            onOpenImage = onOpenImage,
            onOpenFile = { message ->
                viewModel.exportAttachment(message.messageId) { path ->
                    val mime = message.attachment?.mimeType.orEmpty()
                    if (path == null || !openExternally(context, path, mime)) {
                        scope.launch { snackbar.showSnackbar(openFailed) }
                    }
                }
            },
            onJumpToQuoted = viewModel::onJumpToMessage,
        )
    }
}

@Composable
private fun rememberSheetState(
    viewModel: ConversationViewModel,
    actionTarget: MutableState<String?>,
    snackbar: VmSnackbarHostState,
    scope: CoroutineScope,
): ConversationSheetState {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.feature_chat_copied)
    val shareFailed = stringResource(R.string.feature_chat_attachment_open_failed)
    val attachOpen = rememberSaveable { mutableStateOf(false) }
    val picker = rememberAttachmentPicker(viewModel::onAttachmentsPicked)
    return remember(picker) {
        ConversationSheetState(
            attachOpen = attachOpen,
            actionTarget = actionTarget,
            picker = picker,
            onCopy = { state, messageId ->
                clipboard.setText(AnnotatedString(state.textOf(messageId)))
                if (copyNeedsConfirmation) scope.launch { snackbar.showSnackbar(copied) }
            },
            onShare = { state, messageId ->
                val mime = state.attachmentMimeOf(messageId)
                viewModel.exportAttachment(messageId) { path ->
                    if (path == null || !shareExternally(context, path, mime)) {
                        scope.launch { snackbar.showSnackbar(shareFailed) }
                    }
                }
            },
        )
    }
}

/**
 * Photos and video go through the system Photo Picker, which needs no storage permission
 * and never shows the app the rest of the gallery; anything else goes through `OpenDocument`.
 *
 * All three take several items at once. The single-item contracts closed the picker on the first
 * tap, so sending an album meant reopening it once per photo.
 */
@Composable
private fun rememberAttachmentPicker(onPicked: (List<String>) -> Unit): AttachmentPicker {
    val media = remember { ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_MEDIA) }
    val photo = rememberLauncherForActivityResult(media) { uris -> onPicked(uris.map { it.toString() }) }
    val video = rememberLauncherForActivityResult(media) { uris -> onPicked(uris.map { it.toString() }) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onPicked(uris.map { it.toString() })
    }
    return remember(photo, video, file) {
        AttachmentPicker(
            pickPhoto = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            pickVideo = { video.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            pickFile = { file.launch(arrayOf(ANY_MIME)) },
        )
    }
}

/** True when the message is one of ours; decides whether per-member delivery info is offered. */
internal fun ConversationUiState.isOutgoing(messageId: String): Boolean = items
    .filterIsInstance<ChatItem.Message>()
    .firstOrNull { it.messageId == messageId }
    ?.outgoing == true

/** Body of one message, used by the copy action and by the sheet to hide it when empty. */
/** What the long-press sheet may offer for one message, decided in one place. */
internal fun ConversationUiState.abilitiesFor(messageId: String): MessageAbilities {
    val message = items.filterIsInstance<ChatItem.Message>().firstOrNull { it.messageId == messageId }
    val text = message?.text.orEmpty()
    val alive = message != null && !message.deleted
    return MessageAbilities(
        // Nothing to copy from a bare photo or file bubble, or from a tombstone.
        canCopy = alive && text.isNotBlank(),
        // Only our own words, and only while there are still words: an attachment's caption is
        // editable, a photo without one is not.
        canEdit = alive && message.outgoing && text.isNotBlank(),
        canDeleteForEveryone = alive && message.outgoing,
        // Only an attachment that has actually landed: there is no file to hand on until then.
        canShare = alive && message.attachment?.available == true,
    )
}

/** MIME type of a message's attachment, for the share chooser; blank when it has none. */
internal fun ConversationUiState.attachmentMimeOf(messageId: String): String = items
    .filterIsInstance<ChatItem.Message>()
    .firstOrNull { it.messageId == messageId }
    ?.attachment
    ?.mimeType
    .orEmpty()

internal fun ConversationUiState.textOf(messageId: String): String = items
    .filterIsInstance<ChatItem.Message>()
    .firstOrNull { it.messageId == messageId }
    ?.text
    .orEmpty()
