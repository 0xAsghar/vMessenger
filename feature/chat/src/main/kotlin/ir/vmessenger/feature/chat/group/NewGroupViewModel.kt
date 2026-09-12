package ir.vmessenger.feature.chat.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.usecase.contact.ObserveContactsUseCase
import ir.vmessenger.domain.usecase.group.CreateGroupUseCase
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SUBSCRIBE_TIMEOUT_MS = 5_000L

/** Everything typed or ticked so far, kept as one flow so the state assembles from two. */
private data class NewGroupDraft(
    val selected: ImmutableSet<String> = persistentSetOf(),
    val query: String = "",
    val name: String = "",
    val step: NewGroupStep = NewGroupStep.PickMembers,
    val creating: Boolean = false,
    val message: UiMessage? = null,
)

/**
 * "New group": pick approved contacts, name the group, create it.
 *
 * The member cap is applied while choosing rather than on create. There is no server to
 * reject an oversized group, so a create that is one person over fails halfway through a
 * fan-out — after some members already hold a snapshot nobody else agrees with.
 */
@HiltViewModel
class NewGroupViewModel @Inject constructor(
    observeContacts: ObserveContactsUseCase,
    private val createGroup: CreateGroupUseCase,
) : ViewModel() {

    private val draft = MutableStateFlow(NewGroupDraft())
    private val created = MutableStateFlow<String?>(null)

    /** Set once the group exists; the screen opens its conversation and pops itself. */
    val createdConversationId: StateFlow<String?> = created.asStateFlow()

    // null until the database has answered, which is what separates the skeleton from a
    // genuinely empty contact list.
    private val contacts: Flow<List<GroupPickerContact>?> = observeContacts()
        .map<List<Contact>, List<GroupPickerContact>?> { list -> list.toPickerContacts() }
        .onStart { emit(null) }

    val uiState: StateFlow<NewGroupUiState> = combine(contacts, draft) { loaded, form ->
        NewGroupUiState(
            picker = GroupPickerState(
                contacts = loaded.orEmpty().toImmutableList(),
                selected = form.selected,
                query = form.query,
                loading = loaded == null,
            ),
            step = form.step,
            name = form.name,
            creating = form.creating,
            message = form.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), NewGroupUiState())

    fun onQueryChange(value: String) = draft.update { it.copy(query = value) }

    fun onToggleMember(contactId: String) = draft.update {
        it.copy(selected = it.selected.toggleWithin(GroupLimits.MAX_OTHER_MEMBERS, contactId))
    }

    // Stopping at the cap as the user types beats accepting a longer name and rejecting it
    // once the create button is pressed.
    fun onNameChange(value: String) = draft.update { it.copy(name = value.take(GroupLimits.MAX_NAME_LENGTH)) }

    fun onContinue() = draft.update {
        if (it.selected.isEmpty()) it else it.copy(step = NewGroupStep.NameGroup)
    }

    fun onBackToMembers() = draft.update { it.copy(step = NewGroupStep.PickMembers) }

    fun onCreate() {
        val form = draft.value
        if (form.creating || form.selected.isEmpty() || !GroupLimits.isValidName(form.name)) return
        draft.update { it.copy(creating = true) }
        viewModelScope.launch { runCreate(form.name.trim(), form.selected.toList()) }
    }

    fun onMessageShown() = draft.update { it.copy(message = null) }

    fun onCreatedHandled() {
        created.value = null
    }

    private suspend fun runCreate(name: String, memberContactIds: List<String>) {
        when (val result = createGroup(name, memberContactIds)) {
            is AppResult.Success -> created.value = result.data
            // The selection and the name survive the failure so the user can retry without
            // rebuilding the group from scratch.
            is AppResult.Error -> draft.update {
                it.copy(creating = false, message = UiMessage.Failure(result.error))
            }
        }
    }
}
