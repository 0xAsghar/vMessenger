package ir.vmessenger.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.usecase.identity.HasIdentityUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SplashDestination {
    Loading,
    Home,
    CreateIdentity,
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val hasIdentityUseCase: HasIdentityUseCase,
) : ViewModel() {
    private val _destination = MutableStateFlow(SplashDestination.Loading)
    val destination: StateFlow<SplashDestination> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            val hasIdentity = hasIdentityUseCase()
            _destination.value = if (hasIdentity) {
                SplashDestination.Home
            } else {
                SplashDestination.CreateIdentity
            }
        }
    }
}
