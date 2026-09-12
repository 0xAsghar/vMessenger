package ir.vmessenger.feature.chat

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.notifications.ActiveConversationTracker
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.AttachmentType
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.Group
import ir.vmessenger.domain.model.GroupMember
import ir.vmessenger.domain.model.MessageDirection
import ir.vmessenger.domain.repository.ConversationRepository
import ir.vmessenger.domain.usecase.chat.DeleteMessageForMeUseCase
import ir.vmessenger.domain.usecase.chat.MarkConversationReadUseCase
import ir.vmessenger.domain.usecase.chat.ObserveChatListUseCase
import ir.vmessenger.domain.usecase.chat.ObserveDraftUseCase
import ir.vmessenger.domain.usecase.chat.ObserveMessagesPagedUseCase
import ir.vmessenger.domain.usecase.chat.RetryMessageUseCase
import ir.vmessenger.domain.usecase.chat.SaveDraftUseCase
import ir.vmessenger.domain.usecase.chat.SendMessageUseCase
import ir.vmessenger.domain.usecase.chat.SendVoiceUseCase
import ir.vmessenger.domain.usecase.contact.ObserveContactsUseCase
import ir.vmessenger.domain.usecase.group.ObserveGroupMembersUseCase
import ir.vmessenger.domain.usecase.group.ObserveGroupUseCase
import ir.vmessenger.feature.chat.group.hexToBytes
import ir.vmessenger.feature.chat.voice.VoicePlaybackController
import ir.vmessenger.feature.chat.voice.VoiceRecorder
import ir.vmessenger.feature.chat.voice.VoiceSession
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.Calendar
import javax.inject.Inject
/**
 * One conversation.
 *
 * The message window starts at [PAGE_SIZE] and grows by the same amount when the user
 * reaches the top, so a chat with ten thousand messages costs the same as a fresh one.
 * The list the screen renders — bubbles *and* day separators — is assembled here, and
 * every timestamp is formatted here too: composition does no work per frame.
 */
@HiltViewModel
// TooManyFunctions: one entry point per conversation action; a bag of lambdas would only hide them.
// LongParameterList: Hilt injection, one dependency per capability, with nothing to hoist.
@Suppress("TooManyFunctions", "LongParameterList")
class ConversationViewModel @Inject constructor(
    // The repository covers what has no use case yet: attachment streams, the message
    // count behind "load earlier" and indexOfMessage behind "jump to the quoted message".
    private val conversationRepository: ConversationRepository,
    private val sendMessage: SendMessageUseCase,
    private val saveDraft: SaveDraftUseCase,
    private val deleteMessageForMe: DeleteMessageForMeUseCase,
    private val retryMessage: RetryMessageUseCase,
    private val markConversationRead: MarkConversationReadUseCase,
    private val sendVoice: SendVoiceUseCase,
    // The application context: the recorder needs a cache directory and an audio source,
    // both process-scoped, so nothing here outlives the process or leaks an activity.
    @ApplicationContext context: Context,
    voicePlayback: VoicePlaybackController,
    observeChatList: ObserveChatListUseCase,
    observeGroup: ObserveGroupUseCase,
    observeGroupMembers: ObserveGroupMembersUseCase,
    observeContacts: ObserveContactsUseCase,
    observeMessagesPaged: ObserveMessagesPagedUseCase,
    observeDraft: ObserveDraftUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val pageLimit = MutableStateFlow(PAGE_SIZE)
    private val hasMore = MutableStateFlow(false)
    private val typedText = MutableStateFlow<String?>(null)
    private val replyTo = MutableStateFlow<ReplyQuoteUi?>(null)
    private val scrollTarget = MutableStateFlow<String?>(null)
    private var draftJob: Job? = null
    private var growingWindow = false

    /** Message the screen should scroll to once (tapping a reply quote); cleared by [onScrollHandled]. */
    val scrollToMessageId: StateFlow<String?> = scrollTarget.asStateFlow()

    /** The conversation's group, and its members, or nulls while this is a 1:1 chat. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val group: Flow<GroupState> = observeChatList()
        .map { summaries -> summaries.firstOrNull { it.id == conversationId }?.groupId }
        .distinctUntilChanged()
        .flatMapLatest { groupId ->
            if (groupId == null) {
                flowOf(GroupState())
            } else {
                combine(observeGroup(groupId), observeGroupMembers(groupId), ::GroupState)
            }
        }

    private val header: Flow<ConversationHeaderUi> =
        combine(observeChatList(), observeContacts(), group) { summaries, contacts, groupState ->
            buildHeader(summaries, contacts, groupState)
        }

    /** Recording and playback for this conversation; see [VoiceSession] for why it is not inlined. */
    val voice = VoiceSession(
        recorder = VoiceRecorder(context, viewModelScope),
        playback = voicePlayback,
        scope = viewModelScope,
        ports = VoiceSession.VoicePorts(
            send = { recording ->
                sendVoice(conversationId, recording.filePath, recording.durationMs, recording.waveform)
            },
            open = ::exportVoice,
            upNext = ::voiceMessagesAfter,
            markPlayed = conversationRepository::markVoicePlayed,
        ),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val items: Flow<ImmutableList<ChatItem>?> = pageLimit
        .flatMapLatest { limit ->
            observeMessagesPaged(conversationId, limit)
                .onEach { window -> hasMore.value = window.size >= limit }
        }
        .map<List<ChatMessage>, ImmutableList<ChatItem>?> { window -> buildItems(window) }
        .onStart { emit(null) }

    private val composer: Flow<ComposerUiState> =
        combine(typedText, observeDraft(conversationId), replyTo) { typed, saved, reply ->
            ComposerUiState(text = typed ?: saved, replyTo = reply)
        }

    private val progress: Flow<Map<String, AttachmentProgress>> =
        conversationRepository.observeAttachmentProgress(conversationId)

    val uiState: StateFlow<ConversationUiState> = combine(
        header,
        items,
        composer,
        progress,
        hasMore,
    ) { headerUi, loaded, composerState, transfers, more ->
        ConversationUiState(
            header = headerUi,
            items = loaded ?: persistentListOf(),
            pendingIncoming = pendingIncoming(loaded, transfers),
            attachmentProgress = transfers.toImmutableMap(),
            composer = composerState.copy(enabled = !headerUi.blocked && !headerUi.closed),
            hasMore = more,
            loading = loaded == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), ConversationUiState())

    /**
     * Called while the conversation is on screen: clears the unread count, marks the
     * incoming messages read (which sends read receipts when the user allows them) and
     * tells the notifier to stay quiet for this conversation.
     */
    fun onVisible() {
        ActiveConversationTracker.activeConversationId = conversationId
        viewModelScope.launch { markConversationRead(conversationId) }
    }

    /** Called when the conversation leaves the screen; notifications resume. */
    fun onHidden() {
        ActiveConversationTracker.clear(conversationId)
        voice.detach()
    }

    fun onTextChange(text: String) {
        typedText.value = text
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            saveDraft(conversationId, text)
        }
    }

    fun onSend() {
        val text = uiState.value.composer.text.trim()
        if (text.isEmpty()) return
        val quoted = replyTo.value?.messageId
        typedText.value = ""
        replyTo.value = null
        draftJob?.cancel()
        viewModelScope.launch {
            saveDraft(conversationId, "")
            sendMessage(conversationId, text, quoted)
        }
    }

    fun onAttachmentPicked(uri: String) {
        viewModelScope.launch { conversationRepository.sendAttachment(conversationId, uri) }
    }

    fun onReply(messageId: String) {
        replyTo.value = uiState.value.items
            .filterIsInstance<ChatItem.Message>()
            .firstOrNull { it.messageId == messageId }
            ?.toQuote()
    }

    fun onClearReply() {
        replyTo.value = null
    }

    fun onDeleteMessage(messageId: String) {
        viewModelScope.launch { deleteMessageForMe(messageId) }
    }

    fun onRetry(messageId: String) {
        viewModelScope.launch { retryMessage(messageId) }
    }

    /** Grows the window by one page, but only when the database really holds more. */
    fun onLoadEarlier() {
        if (growingWindow || !hasMore.value) return
        growingWindow = true
        viewModelScope.launch {
            val limit = pageLimit.value
            if (conversationRepository.countMessages(conversationId) > limit) {
                pageLimit.value = limit + PAGE_SIZE
            } else {
                hasMore.value = false
            }
            growingWindow = false
        }
    }

    /** Widens the window until [messageId] is inside it, then asks the screen to scroll there. */
    fun onJumpToMessage(messageId: String) {
        viewModelScope.launch {
            // -1 means the quote points at a message this device no longer has (deleted for
            // me, or quoted from another chat); there is nowhere to scroll to.
            val index = conversationRepository.indexOfMessage(conversationId, messageId)
            if (index >= 0) {
                if (index >= pageLimit.value) {
                    pageLimit.value = (index / PAGE_SIZE + 1) * PAGE_SIZE
                }
                scrollTarget.value = messageId
            }
        }
    }

    fun onScrollHandled() {
        scrollTarget.value = null
    }

    /** Plaintext stream of an attachment, for the in-app image pipeline. Never written to disk. */
    suspend fun openAttachmentStream(messageId: String): InputStream? =
        conversationRepository.openAttachment(messageId)

    /** Exports an attachment to the view cache and hands its plaintext path to [onReady]. */
    fun exportAttachment(messageId: String, onReady: (String?) -> Unit) {
        viewModelScope.launch {
            val path = when (val result = conversationRepository.exportAttachmentForViewing(messageId)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> null
            }
            onReady(path)
        }
    }

    /**
     * The next voice messages this one should flow into: later in the conversation (the list
     * is newest-first, so that is *earlier* in it) and not yet played.
     */
    private fun voiceMessagesAfter(messageId: String): List<String> {
        val items = uiState.value.items.filterIsInstance<ChatItem.Message>()
        val index = items.indexOfFirst { it.messageId == messageId }
        if (index <= 0) return emptyList()
        return items.take(index).reversed()
            .filter { it.attachment?.type == AttachmentType.AUDIO && it.attachment.available }
            .map { it.messageId }
    }

    /** Decrypts a voice message for the player, which owns the temp file from then on. */
    private suspend fun exportVoice(messageId: String): String? =
        when (val result = conversationRepository.exportAttachmentForViewing(messageId)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> null
        }

    private fun buildHeader(
        summaries: List<ConversationSummary>,
        contacts: List<Contact>,
        groupState: GroupState,
    ): ConversationHeaderUi {
        val summary = summaries.firstOrNull { it.id == conversationId }
        val contact = contacts.firstOrNull { it.id == summary?.contactId }
        val groupInfo = groupState.group
        return ConversationHeaderUi(
            title = groupInfo?.name ?: summary?.contactName ?: contact?.displayName.orEmpty(),
            seed = IdentitySeed(
                groupInfo?.avatarSeed?.toByteArray() ?: summary?.identityHash ?: contact?.identityHash ?: ByteArray(0),
            ),
            contactId = summary?.contactId ?: contact?.id,
            groupId = groupInfo?.id,
            memberNames = groupState.members
                .takeIf { it.isNotEmpty() }
                ?.joinToString(NAME_SEPARATOR) { it.displayName },
            closed = groupInfo?.closed == true,
            verified = contact?.verified == true,
            keyChangePending = contact?.keyChangePending == true,
            blocked = contact?.blocked == true,
        )
    }

    /** The group behind this conversation; both fields are null for a 1:1 chat. */
    private data class GroupState(val group: Group? = null, val members: List<GroupMember> = emptyList())

    private companion object {
        const val PAGE_SIZE = 60
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        const val DRAFT_DEBOUNCE_MS = 400L

        /** Persian comma: the separator a member list is written with. */
        const val NAME_SEPARATOR = "، "
    }
}

/**
 * Turns the newest-first window into the rendered list. A day separator is emitted *after*
 * the oldest message of each day, because `reverseLayout` draws increasing indices upwards,
 * which puts the separator above the day it introduces.
 */
private fun buildItems(messages: List<ChatMessage>): ImmutableList<ChatItem> {
    val now = System.currentTimeMillis()
    val items = ArrayList<ChatItem>(messages.size + DAY_SEPARATOR_HEADROOM)
    messages.forEachIndexed { index, message ->
        val day = dayKey(message.createdAtUnixMs)
        val older = messages.getOrNull(index + 1)
        items += if (message.isSystemEvent) {
            ChatItem.System(message.messageId, message.text)
        } else {
            message.toItem(startsSenderRun = message.startsRunAfter(older, day))
        }
        if (older == null || day != dayKey(older.createdAtUnixMs)) {
            items += ChatItem.Day(day, VmDateFormat.daySeparator(message.createdAtUnixMs, now))
        }
    }
    return items.toImmutableList()
}

/**
 * True when this message opens a run by one sender in a group.
 *
 * The window is newest-first, so [older] is the message *above* this one on screen; the name and
 * avatar go on the run's oldest message, which is where the eye starts reading it. A new day
 * starts a new run, because a separator has already broken the thread visually.
 */
private fun ChatMessage.startsRunAfter(older: ChatMessage?, day: Int): Boolean {
    if (senderIdentityHash == null) return false
    return older == null ||
        older.senderIdentityHash != senderIdentityHash ||
        dayKey(older.createdAtUnixMs) != day
}

private fun pendingIncoming(
    items: List<ChatItem>?,
    transfers: Map<String, AttachmentProgress>,
): ImmutableList<String> {
    if (transfers.isEmpty()) return persistentListOf()
    val known = items.orEmpty().filterIsInstance<ChatItem.Message>().mapTo(HashSet()) { it.messageId }
    return transfers
        .filter { (id, value) -> value.direction == MessageDirection.INCOMING && id !in known }
        .keys
        .toImmutableList()
}

private const val DAY_SEPARATOR_HEADROOM = 4
private const val DAYS_PER_YEAR_SLOT = 1_000

/** Calendar day in the device time zone; Jalali and Gregorian share midnight, so this groups both. */
private fun dayKey(ms: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = ms
    return calendar.get(Calendar.YEAR) * DAYS_PER_YEAR_SLOT + calendar.get(Calendar.DAY_OF_YEAR)
}

private fun ChatMessage.toItem(startsSenderRun: Boolean): ChatItem.Message {
    val outgoing = direction == MessageDirection.OUTGOING
    return ChatItem.Message(
        messageId = messageId,
        outgoing = outgoing,
        text = text,
        time = VmDateFormat.time(createdAtUnixMs),
        ticks = if (outgoing) status.toTicks() else null,
        attachment = attachment?.let {
            AttachmentUi(
                type = it.type,
                fileName = it.fileName,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                available = it.localPath != null,
                durationMs = it.durationMs,
                waveform = it.waveform,
                unplayed = !it.played,
            )
        },
        reply = replyTo?.let { ReplyQuoteUi(it.messageId, it.senderIsMe, it.preview, it.contentType) },
        failed = status == DeliveryStatus.FAILED,
        errorCode = lastError,
        senderName = senderName,
        senderSeed = senderIdentityHash?.let { IdentitySeed(hexToBytes(it)) },
        startsSenderRun = startsSenderRun,
    )
}

private fun ChatItem.Message.toQuote(): ReplyQuoteUi = ReplyQuoteUi(
    messageId = messageId,
    senderIsMe = outgoing,
    preview = text.ifBlank { attachment?.fileName.orEmpty() },
    kind = attachment.previewKind(),
)
