package ir.vmessenger.feature.contacts

import ir.vmessenger.core.designsystem.component.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a snackbar across a pop.
 *
 * Deleting a contact closes the detail screen, so its own ViewModel is gone before anything can
 * be shown; the confirmation has to appear on the list the user lands back on. A buffered channel
 * (not a shared flow) gives at-most-once delivery: the message waits until a screen collects it
 * and is never replayed after a rotation.
 */
@Singleton
class ContactMessageBus @Inject constructor() {
    private val channel = Channel<UiMessage>(Channel.BUFFERED)

    val messages: Flow<UiMessage> = channel.receiveAsFlow()

    suspend fun send(message: UiMessage) {
        channel.send(message)
    }
}
