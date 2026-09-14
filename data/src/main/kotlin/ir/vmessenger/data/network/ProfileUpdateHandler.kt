package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.ProfileUpdate
import ir.vmessenger.data.attachment.AttachmentStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Peer-supplied strings are bounded before storage, as group names already are. */
private const val MAX_DISPLAY_NAME_CHARS = 64

/** Comfortably above a 256px WebP and well under the relay's 64 KiB frame limit. */
private const val MAX_AVATAR_BYTES = 48 * 1024

private const val DEFAULT_AVATAR_MIME = "image/webp"

/**
 * A contact telling us their display name or photo changed.
 *
 * Until this existed the name reached us exactly once, inside the pairing handshake, so a contact
 * who renamed themselves stayed under their old name on our device forever — the single most
 * visible gap in the protocol.
 *
 * Two rules carry over from pairing and are not re-litigated here. A name the *user* typed always
 * wins: if the local display name is anything other than the raw user hash, it is an alias and the
 * peer does not get to overwrite it. And the sender is the authenticated session's contact, never
 * anything in the envelope.
 */
@Singleton
class ProfileUpdateHandler @Inject constructor(
    private val contactDao: ContactDao,
    private val attachmentStore: AttachmentStore,
    private val crypto: CryptoEngine,
) {
    suspend fun handle(contactId: String, envelope: MessageEnvelope) {
        val update = envelope.profileUpdate
        val contact = contactDao.getById(contactId) ?: return
        // Monotonic per sender, so an update that overtook a newer one in a mailbox hand-off
        // cannot reinstate a stale name or photo. Revision 0 is "never heard from", not a value.
        val revision = update.revision.toLong()
        if (contact.avatarRevision != 0L && revision <= contact.avatarRevision) {
            AppLogger.warn("Messaging", "stale profile update ignored contact=$contactId")
            return
        }
        contactDao.update(
            contact.copy(
                displayName = resolveName(contact, update),
                avatarPath = resolveAvatar(contact, update),
                avatarRevision = revision,
            ),
        )
    }

    private fun resolveName(contact: ContactEntity, update: ProfileUpdate): String {
        val hasCustomAlias = contact.displayName.isNotBlank() && contact.displayName != contact.userHash
        val offered = update.displayName.trim().take(MAX_DISPLAY_NAME_CHARS)
        return if (hasCustomAlias || offered.isBlank()) contact.displayName else offered
    }

    /**
     * Stores the photo in the same encrypted container attachments use, so a contact's face is no
     * more readable on a lost device than their messages are. An empty payload means the peer
     * cleared their photo, which is a real state and not a no-op.
     */
    private suspend fun resolveAvatar(contact: ContactEntity, update: ProfileUpdate): String? {
        val bytes = update.avatar.toByteArray()
        return when {
            // An empty payload means the peer cleared their photo — a real state, not a no-op.
            bytes.isEmpty() -> {
                contact.avatarPath?.let { attachmentStore.delete(it) }
                null
            }
            // Verified before it is stored: the digest is what the sender committed to, and an
            // image that does not match it is not the one they meant to send. An oversized one is
            // refused outright rather than trusted to be a photo.
            !bytes.isAcceptable(update, contact.id) -> contact.avatarPath
            else -> store(bytes, contact, update)
        }
    }

    private suspend fun store(bytes: ByteArray, contact: ContactEntity, update: ProfileUpdate): String? {
        // importFile consumes (and deletes) its source, so the bytes go through a scratch file
        // rather than a second encryption path invented here.
        val staged = File.createTempFile("avatar", null)
        return try {
            staged.writeBytes(bytes)
            val copied = attachmentStore.importFile(staged, update.avatarMime.ifBlank { DEFAULT_AVATAR_MIME }, "avatar")
            contact.avatarPath?.let { attachmentStore.delete(it) }
            copied.file.absolutePath
        } catch (error: java.io.IOException) {
            AppLogger.warn("Messaging", "avatar store failed contact=${contact.id}: ${error.message}")
            contact.avatarPath
        } finally {
            staged.delete()
        }
    }

    private fun ByteArray.isAcceptable(update: ProfileUpdate, contactId: String): Boolean = when {
        size > MAX_AVATAR_BYTES -> {
            AppLogger.warn("Messaging", "oversized avatar refused size=$size contact=$contactId")
            false
        }
        !crypto.sha256(this).contentEquals(update.avatarSha256.toByteArray()) -> {
            AppLogger.warn("Messaging", "avatar digest mismatch contact=$contactId")
            false
        }
        else -> true
    }
}
