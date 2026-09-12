package ir.vmessenger.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.domain.usecase.identity.HasIdentityUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SplashDestination {
    Loading,
    Home,
    CreateIdentity,
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val hasIdentityUseCase: HasIdentityUseCase,
    private val databaseKeyProvider: DatabaseKeyProvider,
) : ViewModel() {
    private val _destination = MutableStateFlow(SplashDestination.Loading)
    val destination: StateFlow<SplashDestination> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            // The application starts this off the main thread; the splash must
            // wait for it (the call is idempotent) because the identity lookup
            // opens the encrypted database.
            withContext(Dispatchers.IO) { databaseKeyProvider.initialize() }
            val hasIdentity = hasIdentityUseCase()
            _destination.value = if (hasIdentity) {
                SplashDestination.Home
            } else {
                SplashDestination.CreateIdentity
            }
        }
    }
}
