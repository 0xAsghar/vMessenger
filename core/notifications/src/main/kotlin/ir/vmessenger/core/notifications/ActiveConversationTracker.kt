package ir.vmessenger.core.notifications

/**
 * Remembers which conversation is currently open on screen so incoming
 * messages for it are not also raised as notifications. The chat screen sets
 * it on enter and clears it on exit; deleting a contact clears it as well.
 */
object ActiveConversationTracker {
    @Volatile
    var activeConversationId: String? = null

    fun isActive(conversationId: String): Boolean = activeConversationId == conversationId

    /** Clears the tracker only if it still points at [conversationId]. */
    fun clear(conversationId: String) {
        if (activeConversationId == conversationId) activeConversationId = null
    }
}
