package ir.vmessenger.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.usecase.contact.BlockContactUseCase
import ir.vmessenger.domain.usecase.contact.ObserveBlockedContactsUseCase
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val BLOCKED_TIMEOUT_MS = 5_000L

@Immutable
data class BlockedContactRow(
    val id: String,
    val name: String,
    val userHash: String,
    val identityHash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BlockedContactRow
        return id == other.id &&
            name == other.name &&
            userHash == other.userHash &&
            identityHash.contentEquals(other.identityHash)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + userHash.hashCode()
        result = 31 * result + identityHash.contentHashCode()
        return result
    }
}

@Immutable
data class BlockedContactsUiState(
    val loading: Boolean = true,
    val contacts: ImmutableList<BlockedContactRow> = persistentListOf(),
    val pendingUnblock: BlockedContactRow? = null,
)

/**
 * The only place a block can be undone: blocked contacts are hidden from the contacts list by
 * `ContactDao.observeContacts`, so without this screen the action would be one-way.
 */
@HiltViewModel
class BlockedContactsViewModel @Inject constructor(
    observeBlockedContacts: ObserveBlockedContactsUseCase,
    private val blockContact: BlockContactUseCase,
) : ViewModel() {

    private val pending = MutableStateFlow<String?>(null)
    private val unblocked = Channel<String>(Channel.BUFFERED)

    /** Emits the name of each contact that was just unblocked, for the snackbar. */
    val events: Flow<String> = unblocked.receiveAsFlow()

    val uiState: StateFlow<BlockedContactsUiState> = combine(
        observeBlockedContacts(),
        pending,
    ) { contacts, pendingId ->
        val rows = contacts.map {
            BlockedContactRow(
                id = it.id,
                name = it.displayName,
                userHash = it.userHash,
                identityHash = it.identityHash,
            )
        }
        BlockedContactsUiState(
            loading = false,
            contacts = rows.toImmutableList(),
            pendingUnblock = rows.firstOrNull { it.id == pendingId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(BLOCKED_TIMEOUT_MS), BlockedContactsUiState())

    fun onUnblockRequest(contactId: String) {
        pending.value = contactId
    }

    fun dismissDialog() {
        pending.value = null
    }

    fun confirmUnblock() {
        val target = uiState.value.pendingUnblock ?: return
        pending.value = null
        viewModelScope.launch {
            blockContact(target.id, false)
            unblocked.send(target.name)
        }
    }
}
