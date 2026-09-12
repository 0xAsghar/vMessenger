package ir.vmessenger.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import javax.inject.Inject

@Immutable
data class ImageViewerUiState(val messageId: String = "")

/**
 * The full-screen image viewer.
 *
 * It holds no image state of its own: Coil owns the bitmap (memory cache only) and this
 * only hands it a decrypted stream, so the plaintext of a photo never reaches storage.
 */
@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Nothing here changes over time, but the screen contract is the same everywhere:
    // one immutable UiState behind a StateFlow.
    val uiState: StateFlow<ImageViewerUiState> =
        MutableStateFlow(ImageViewerUiState(messageId = checkNotNull(savedStateHandle["messageId"])))
            .asStateFlow()

    suspend fun openAttachmentStream(messageId: String): InputStream? =
        conversationRepository.openAttachment(messageId)
}
