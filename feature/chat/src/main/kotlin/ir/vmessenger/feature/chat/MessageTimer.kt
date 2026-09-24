package ir.vmessenger.feature.chat

import androidx.compose.runtime.Immutable

/**
 * When new messages in a chat erase themselves. Set from the conversation header and applied by
 * [ConversationViewModel] to every kind of message alike — text, photos, files and voice.
 */
@Immutable
sealed interface MessageTimer {
    /** The deadline for a message sent at [now]; null once this timer has nothing left to give. */
    fun deadlineFor(now: Long): Long?

    /** Each message lives [durationMs] from the moment it is sent. */
    data class After(val durationMs: Long) : MessageTimer {
        override fun deadlineFor(now: Long): Long = now + durationMs
    }

    /**
     * Every message sent before [atUnixMs] disappears at [atUnixMs], however late it was sent: one
     * moment for the conversation, rather than one lifetime per message. Past it, there is nothing
     * left to apply, and a message sent then is an ordinary one.
     */
    data class At(val atUnixMs: Long) : MessageTimer {
        override fun deadlineFor(now: Long): Long? = atUnixMs.takeIf { it > now }
    }
}
