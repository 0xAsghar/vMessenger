package ir.vmessenger.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.usecase.contact.BlockContactUseCase
import ir.vmessenger.domain.usecase.contact.DeleteContactUseCase
import ir.vmessenger.domain.usecase.contact.ObserveContactsUseCase
import ir.vmessenger.domain.usecase.contact.UpdateContactAliasUseCase
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    observeContacts: ObserveContactsUseCase,
    private val deleteContact: DeleteContactUseCase,
    private val blockContact: BlockContactUseCase,
    private val updateAlias: UpdateContactAliasUseCase,
    private val getIdentity: GetIdentityUseCase,
) : ViewModel() {
    val contacts: StateFlow<List<Contact>> = observeContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _localPublicKey = MutableStateFlow<ByteArray?>(null)
    val localPublicKey: StateFlow<ByteArray?> = _localPublicKey.asStateFlow()

    init {
        viewModelScope.launch {
            _localPublicKey.value = getIdentity()?.ed25519PublicKey
        }
    }

    suspend fun localIdentityHash(): ByteArray? = getIdentity()?.identityHash

    fun delete(id: String) = viewModelScope.launch { deleteContact(id) }
    fun block(id: String, blocked: Boolean) = viewModelScope.launch { blockContact(id, blocked) }
    fun rename(id: String, alias: String) = viewModelScope.launch { updateAlias(id, alias) }
}
