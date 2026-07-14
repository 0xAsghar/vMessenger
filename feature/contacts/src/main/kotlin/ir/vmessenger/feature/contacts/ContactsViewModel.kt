package ir.vmessenger.feature.contacts

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.data.location.MyLocationSource
import ir.vmessenger.data.network.ContactRequestHandler
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.model.LocationSample
import ir.vmessenger.domain.repository.ContactRequestRepository
import ir.vmessenger.domain.repository.LocationRepository
import ir.vmessenger.domain.usecase.contact.BlockContactUseCase
import ir.vmessenger.domain.usecase.contact.DeleteContactUseCase
import ir.vmessenger.domain.usecase.contact.ObserveContactsUseCase
import ir.vmessenger.domain.usecase.contact.SendContactRequestUseCase
import ir.vmessenger.domain.usecase.contact.UpdateContactAliasUseCase
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A contact plus, when they are sharing their location with us, its position and distance. */
data class ContactListItem(
    val contact: Contact,
    val sharedLocation: LocationSample? = null,
    val distanceMeters: Double? = null,
)

@HiltViewModel
@Suppress("LongParameterList")
class ContactsViewModel @Inject constructor(
    observeContacts: ObserveContactsUseCase,
    contactRequestRepository: ContactRequestRepository,
    locationRepository: LocationRepository,
    myLocationSource: MyLocationSource,
    private val contactRequestHandler: ContactRequestHandler,
    private val sendContactRequest: SendContactRequestUseCase,
    private val deleteContact: DeleteContactUseCase,
    private val blockContact: BlockContactUseCase,
    private val updateAlias: UpdateContactAliasUseCase,
    private val getIdentity: GetIdentityUseCase,
    private val contactRepository: ir.vmessenger.domain.repository.ContactRepository,
) : ViewModel() {
    val items: StateFlow<List<ContactListItem>> = combine(
        observeContacts(),
        locationRepository.observeIncomingLocations(),
        myLocationSource.observe(),
    ) { contacts, incoming, myLocation ->
        contacts.map { contact ->
            val shared = incoming[contact.id]
            ContactListItem(
                contact = contact,
                sharedLocation = shared,
                distanceMeters = distanceOrNull(myLocation, shared),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Hide requests from peers we already approved (a stale PENDING row can
    // linger if the relationship completed through another path, e.g. a mutual
    // add). Such requests are auto-accepted on arrival anyway.
    val pendingRequests: StateFlow<List<ContactRequest>> = combine(
        contactRequestRepository.observePendingRequests(),
        observeContacts(),
    ) { requests, contacts ->
        val approvedHashes = contacts.filter { it.isApproved }.map { it.identityHash }
        requests.filterNot { request ->
            approvedHashes.any { IdentityHashMatcher.matches(it, request.requesterIdentityHash) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _localPublicKey = MutableStateFlow<ByteArray?>(null)
    val localPublicKey: StateFlow<ByteArray?> = _localPublicKey.asStateFlow()

    init {
        viewModelScope.launch {
            _localPublicKey.value = getIdentity()?.ed25519PublicKey
        }
    }

    fun approveRequest(request: ContactRequest) =
        viewModelScope.launch { contactRequestHandler.approveRequest(request) }

    fun rejectRequest(request: ContactRequest) =
        viewModelScope.launch { contactRequestHandler.rejectRequest(request) }

    fun resendRequest(contactId: String) = viewModelScope.launch {
        contactRepository.getContact(contactId)?.let { sendContactRequest(it) }
    }

    fun delete(id: String) = viewModelScope.launch { deleteContact(id) }
    fun block(id: String, blocked: Boolean) = viewModelScope.launch { blockContact(id, blocked) }
    fun rename(id: String, alias: String) = viewModelScope.launch { updateAlias(id, alias) }

    private fun distanceOrNull(mine: ir.vmessenger.data.location.LatLng?, theirs: LocationSample?): Double? {
        if (mine == null || theirs == null) return null
        val result = FloatArray(1)
        Location.distanceBetween(mine.latitude, mine.longitude, theirs.latitude, theirs.longitude, result)
        return result[0].toDouble()
    }
}
