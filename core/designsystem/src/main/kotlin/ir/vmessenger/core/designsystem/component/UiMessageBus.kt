package ir.vmessenger.core.designsystem.component

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a snackbar across a pop.
 *
 * A screen that closes as its last act cannot show its own confirmation: deleting a contact ends
 * the detail screen, and a successful QR scan ends the scanner, so in both cases the ViewModel is
 * gone before anything reaches the user. The message has to appear on whatever the user lands back
 * on. A buffered channel (not a shared flow) gives at-most-once delivery: it waits until a screen
 * collects it and is never replayed after a rotation.
 *
 * Lives in the design system rather than in a feature because the publisher and the collector are
 * now in different modules — pairing publishes, contacts collects. No Hilt plugin is needed here;
 * a plain `@Inject` constructor is enough for the component in `:app` to build it, which is the
 * same arrangement `:domain` uses for its use cases.
 */
@Singleton
class UiMessageBus @Inject constructor() {
    private val channel = Channel<UiMessage>(Channel.BUFFERED)

    val messages: Flow<UiMessage> = channel.receiveAsFlow()

    suspend fun send(message: UiMessage) {
        channel.send(message)
    }
}
