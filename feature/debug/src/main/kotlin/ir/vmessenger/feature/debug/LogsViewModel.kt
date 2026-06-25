package ir.vmessenger.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.logging.LogEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor() : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = AppLogger.entries.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun clear() {
        AppLogger.clear()
    }

    fun snapshotText(): String = AppLogger.snapshotText()
}
