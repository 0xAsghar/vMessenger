package ir.vmessenger.feature.chat.group

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.GroupAuditEntry
import ir.vmessenger.domain.usecase.group.ObserveGroupAuditUseCase
import ir.vmessenger.domain.usecase.group.ObserveGroupUseCase
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One captured revision, as the review screen draws it. */
@Immutable
data class GroupAuditRow(
    val messageId: String,
    val authorName: String?,
    val deleted: Boolean,
    val text: String?,
    val attachmentName: String?,
    val capturedAtUnixMs: Long,
)

@Immutable
data class GroupAuditUiState(
    val loading: Boolean = true,
    /**
     * False when the group's creator has switched retention off, or never on. The screen then says
     * so instead of showing an empty list, which would read as "nobody has edited anything".
     */
    val retentionOn: Boolean = false,
    val entries: ImmutableList<GroupAuditRow> = persistentListOf(),
)

/**
 * Reads what this device captured for one group.
 *
 * There is no fetch and no request: every member receives every group message, so a device that was
 * present holds the revisions it saw. Asking another member's device to serve its copies would mean
 * one member's phone answering queries about a third party's words, which is a larger privacy
 * surface than the feature needs.
 */
@HiltViewModel
class GroupAuditViewModel @Inject constructor(
    observeGroup: ObserveGroupUseCase,
    observeGroupAudit: ObserveGroupAuditUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val groupId: String = savedStateHandle.get<String>(GROUP_ID_KEY).orEmpty()

    val uiState: StateFlow<GroupAuditUiState> =
        combine(observeGroup(groupId), observeGroupAudit(groupId)) { group, entries ->
            GroupAuditUiState(
                loading = false,
                retentionOn = group?.auditRetention == true,
                entries = entries.map(GroupAuditEntry::toRow).toImmutableList(),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
            initialValue = GroupAuditUiState(),
        )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}

private fun GroupAuditEntry.toRow() = GroupAuditRow(
    messageId = messageId,
    authorName = authorName,
    deleted = deleted,
    text = text,
    attachmentName = attachmentName,
    capturedAtUnixMs = capturedAtUnixMs,
)
