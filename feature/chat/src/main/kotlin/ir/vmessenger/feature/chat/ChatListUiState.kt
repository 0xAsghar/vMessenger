package ir.vmessenger.feature.chat

import androidx.compose.runtime.Immutable
import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.domain.model.MessagePreviewKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * One row of the chats tab. Everything that needs formatting ([time]) or a lookup
 * ([ticks]) is resolved in the ViewModel; the row only carries values the composable
 * can render directly, plus the raw [previewKind] so the icon prefix and its Persian
 * label can come from resources.
 */
@Immutable
data class ChatListRow(
    val id: String,
    /** Null for a group row; exactly one of this and [groupId] is set. */
    val contactId: String?,
    val groupId: String?,
    val title: String,
    val seed: IdentitySeed,
    val preview: String?,
    val previewKind: MessagePreviewKind?,
    /** Who sent the last group message; prefixed to the preview as "Name: text". */
    val senderName: String?,
    val ticks: DeliveryTicksState?,
    val time: String,
    val unreadCount: Int,
    val muted: Boolean,
) {
    val isGroup: Boolean get() = groupId != null
}

/** What the chats tab is currently showing. */
@Immutable
data class ChatListUiState(
    val rows: ImmutableList<ChatListRow> = persistentListOf(),
    val query: String = "",
    val searching: Boolean = false,
    val selection: ImmutableSet<String> = persistentSetOf(),
    val loading: Boolean = true,
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()

    /** Empty only when the database really is empty — a search with no hits is a different state. */
    val isEmpty: Boolean get() = !loading && rows.isEmpty() && query.isBlank()

    val isNoResults: Boolean get() = !loading && rows.isEmpty() && query.isNotBlank()

    /** The selection toggle offers "unmute" only when every selected conversation is muted. */
    val selectionMuted: Boolean
        get() = selection.isNotEmpty() && rows.filter { it.id in selection }.all { it.muted }
}
