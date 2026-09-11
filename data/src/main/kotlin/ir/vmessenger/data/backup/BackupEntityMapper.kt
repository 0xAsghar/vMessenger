package ir.vmessenger.data.backup

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.backup.v1.BackupContact
import ir.vmessenger.core.proto.backup.v1.BackupMessage
import java.util.UUID

internal const val BACKUP_KEY_SIZE = 32
internal const val BACKUP_IDENTITY_HASH_SIZE = 32

/**
 * Maps a backed-up contact to a row. Returns null when the public key does not hash to [identityHash]
 * (corrupt entry). Hash-added contacts (placeholder public key) are restored as [ContactRelationshipStatus.PENDING_OUT]
 * so the contact request is re-sent; `userHash` is always recomputed from the identity hash.
 */
internal fun BackupContact.toContactEntity(identityHash: ByteArray, now: Long): ContactEntity? {
    val publicKey = ed25519Public.toByteArray()
    val placeholder = publicKey.isEmpty() || IdentityHashMatcher.isPlaceholderPublicKey(publicKey)
    val keyMatchesHash = placeholder ||
        (
            publicKey.size == BACKUP_KEY_SIZE &&
                UserHashEncoder.identityHashFromPublicKey(publicKey).contentEquals(identityHash)
            )
    if (!keyMatchesHash) return null
    val userHash = UserHashEncoder.encode(identityHash)
    return ContactEntity(
        id = id.ifBlank { UUID.randomUUID().toString() },
        identityHash = identityHash,
        ed25519Public = if (placeholder) ByteArray(BACKUP_KEY_SIZE) else publicKey,
        x25519StaticPublic = x25519StaticPublic.toByteArray().takeIf { !placeholder && it.size == BACKUP_KEY_SIZE },
        userHash = userHash,
        displayName = displayName.ifBlank { userHash },
        verified = verified && !placeholder,
        blocked = blocked,
        relationshipStatus = if (placeholder) {
            ContactRelationshipStatus.PENDING_OUT
        } else {
            parseEnum(relationshipStatus, ContactRelationshipStatus.PENDING_OUT)
        },
        createdAtUnixMs = createdAtUnixMs.takeIf { it > 0 } ?: now,
        lastSeenUnixMs = null,
    )
}

/**
 * Maps a backed-up message to a row for [conversationId]. Attachment metadata is kept but the file itself
 * is not part of the bundle, so `attachmentPath` is null. Messages still queued at export time have no
 * outbox entry on the new device and are restored as FAILED rather than stuck in QUEUED.
 */
internal fun BackupMessage.toMessageEntity(conversationId: String, now: Long): MessageEntity? {
    if (messageId.isBlank()) return null
    val direction = parseEnum(direction, MessageDirection.INCOMING)
    val fallbackStatus = if (direction == MessageDirection.INCOMING) DeliveryStatus.DELIVERED else DeliveryStatus.SENT
    val status = parseEnum(status, fallbackStatus)
        .let { if (it == DeliveryStatus.QUEUED) DeliveryStatus.FAILED else it }
    val hasAttachment = attachmentName.isNotEmpty()
    return MessageEntity(
        messageId = messageId,
        conversationId = conversationId,
        direction = direction,
        contentType = parseEnum(contentType, MessageContentType.TEXT),
        body = body.takeIf { it.isNotEmpty() },
        replyToMessageId = replyToMessageId.takeIf { it.isNotEmpty() },
        status = status,
        createdAtUnixMs = createdAtUnixMs.takeIf { it > 0 } ?: now,
        sentAtUnixMs = sentAtUnixMs.takeIf { it > 0 },
        deliveredAtUnixMs = deliveredAtUnixMs.takeIf { it > 0 },
        readAtUnixMs = readAtUnixMs.takeIf { it > 0 },
        attachmentName = attachmentName.takeIf { hasAttachment },
        attachmentMimeType = attachmentMimeType.takeIf { hasAttachment && it.isNotEmpty() },
        attachmentSizeBytes = attachmentSizeBytes.takeIf { hasAttachment },
        attachmentPath = null,
    )
}

internal inline fun <reified T : Enum<T>> parseEnum(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback
