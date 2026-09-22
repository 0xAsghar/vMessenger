package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A verified contact asking us to share our location.
 *
 * All this does is raise the prompt. Nothing here starts the location service, grants that contact
 * access or touches the share state — the person holding the phone answers, from the conversation
 * the notification opens. Whether the sender was even allowed to ask was already decided by
 * [InboundPolicy], which requires a verified contact.
 */
@Singleton
class GpsBuzzerHandler @Inject constructor(
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val notifier: IncomingMessageNotifier,
) {
    suspend fun handle(contactId: String, envelope: MessageEnvelope) {
        if (!envelope.hasGpsBuzzer()) return
        val contact = contactDao.getById(contactId)
        // No conversation yet means nowhere to send the user: a contact who has never exchanged a
        // message can still ask, but there is no thread for the notification to open.
        val conversationId = conversationDao.getByContactId(contactId)?.id
        if (contact == null || conversationId == null) return
        AppLogger.info(TAG, "location share requested by contact=$contactId")
        notifier.notifyLocationRequest(contact.displayName, conversationId)
    }

    private companion object {
        const val TAG = "Location"
    }
}
