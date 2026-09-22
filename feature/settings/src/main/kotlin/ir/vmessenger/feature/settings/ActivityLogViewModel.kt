package ir.vmessenger.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.data.activity.ActivityLogExport
import ir.vmessenger.data.activity.ActivityLogFormat
import ir.vmessenger.data.activity.ActivityLogger
import ir.vmessenger.domain.model.ActivityEvent
import ir.vmessenger.domain.model.ActivityEventKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One logged event, as the screen draws it. */
@Immutable
data class ActivityLogRow(
    val id: Long,
    val kind: ActivityEventKind,
    val detail: String?,
    val atUnixMs: Long,
)

/** A rendered export, waiting to be handed to the share sheet. */
@Immutable
data class ActivityLogExportReady(
    val format: ActivityLogFormat,
    val content: String,
)

@HiltViewModel
class ActivityLogViewModel @Inject constructor(
    private val activityLogger: ActivityLogger,
) : ViewModel() {
    val entries: StateFlow<ImmutableList<ActivityLogRow>> = activityLogger.observe()
        .map { rows -> rows.map(ActivityEvent::toRow).toImmutableList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
            initialValue = persistentListOf(),
        )

    private val _export = MutableStateFlow<ActivityLogExportReady?>(null)

    /** Non-null exactly once per request, so the share sheet opens once per tap. */
    val export: StateFlow<ActivityLogExportReady?> = _export.asStateFlow()

    /**
     * Renders the log off the main thread and hands it back. The file is written and shared by the
     * screen, which is where the FileProvider and the chooser live.
     */
    fun requestExport(format: ActivityLogFormat) {
        viewModelScope.launch {
            val rendered = ActivityLogExport.render(activityLogger.recent(), format)
            _export.value = ActivityLogExportReady(format, rendered)
        }
    }

    fun onExportHandled() {
        _export.value = null
    }

    /** The user clearing their own log. Nothing else empties it short of a wipe. */
    fun clear() {
        viewModelScope.launch { activityLogger.clear() }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}

private fun ActivityEvent.toRow() = ActivityLogRow(
    id = id,
    kind = kind,
    detail = detail,
    atUnixMs = atUnixMs,
)
