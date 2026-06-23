package ir.vmessenger.feature.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.LocationSample
import ir.vmessenger.domain.repository.LocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocationUiState(
    val sharing: Boolean = false,
    val shareId: String? = null,
    val latestSample: LocationSample? = null,
)

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    fun toggleSharing() {
        viewModelScope.launch {
            if (_uiState.value.sharing) {
                _uiState.value.shareId?.let { locationRepository.stopSharing(it) }
                _uiState.update { it.copy(sharing = false, shareId = null) }
            } else {
                when (val result = locationRepository.startSharing(contactId = "self")) {
                    is ir.vmessenger.core.common.AppResult.Success -> {
                        _uiState.update { it.copy(sharing = true, shareId = result.data) }
                    }
                    is ir.vmessenger.core.common.AppResult.Error -> Unit
                }
            }
        }
    }
}
