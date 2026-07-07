package ir.vmessenger.feature.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.data.network.LocationSharingCoordinator
import ir.vmessenger.domain.model.ContactRelationshipStatus
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.LocationAccessRepository
import ir.vmessenger.domain.repository.LocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactAccessItem(
    val contactId: String,
    val displayName: String,
    val granted: Boolean,
)

data class LocationUiState(
    val sharing: Boolean = false,
    val contacts: List<ContactAccessItem> = emptyList(),
    val samples: Map<String, ir.vmessenger.domain.model.LocationSample> = emptyMap(),
)

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val locationAccessRepository: LocationAccessRepository,
    private val locationRepository: LocationRepository,
    private val locationSharingCoordinator: LocationSharingCoordinator,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                contactRepository.observeContacts(),
                locationAccessRepository.observeAll(),
                locationRepository.observeActiveShares(),
                locationRepository.observeLatestSamples(),
            ) { contacts, access, activeShareIds, samples ->
                val approved = contacts.filter {
                    it.relationshipStatus == ContactRelationshipStatus.APPROVED
                }
                val items = approved.map { contact ->
                    ContactAccessItem(
                        contactId = contact.id,
                        displayName = contact.displayName,
                        granted = access[contact.id] == true,
                    )
                }
                LocationUiState(
                    contacts = items,
                    sharing = activeShareIds.isNotEmpty(),
                    samples = samples,
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun toggleAccess(contactId: String, granted: Boolean) {
        viewModelScope.launch {
            locationAccessRepository.setAccess(contactId, granted)
        }
    }

    fun toggleSharing() {
        viewModelScope.launch {
            if (_uiState.value.sharing) {
                locationSharingCoordinator.stopAllSharing()
            } else {
                locationSharingCoordinator.startSharingToGrantedContacts()
            }
        }
    }
}
