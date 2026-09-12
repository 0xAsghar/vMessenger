package ir.vmessenger.data.repository

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity

suspend fun ContactDao.findByIdentityHash(identityHash: ByteArray): ContactEntity? {
    getByIdentityHash(identityHash)?.let { return it }
    return getAll().firstOrNull { IdentityHashMatcher.matches(it.identityHash, identityHash) }
}

suspend fun ContactDao.findContactForInbound(
    identityPub: ByteArray,
    identityHash: ByteArray,
): ContactEntity? {
    if (!IdentityHashMatcher.isPlaceholderPublicKey(identityPub)) {
        getByEd25519Public(identityPub)?.let { return it }
    }
    return findByIdentityHash(identityHash)
        ?: getAll().firstOrNull { contact ->
            IdentityHashMatcher.isPlaceholderPublicKey(contact.ed25519Public) &&
                IdentityHashMatcher.matches(contact.identityHash, identityHash)
        }
}

/** True when the contact carries a real (non-null, non-placeholder) pinned X25519 static key. */
fun ContactEntity.hasPinnedStaticKey(): Boolean =
    x25519StaticPublic?.let { !IdentityHashMatcher.isPlaceholderPublicKey(it) } == true

/**
 * True when [presentedStaticPub] differs from this contact's pinned X25519 static key.
 * A contact without a pin (hash-added, never seen) never conflicts: its key is learned on first use.
 */
fun ContactEntity.conflictsWithPinnedStaticKey(presentedStaticPub: ByteArray): Boolean =
    hasPinnedStaticKey() && x25519StaticPublic?.contentEquals(presentedStaticPub) == false

/**
 * Trust-on-first-use: fills in keys the contact does not have yet, after a
 * handshake authenticated them. A placeholder (all-zero) ed25519 key of a
 * hash-added contact is replaced together with its full identity hash; a
 * null or placeholder X25519 static key is pinned. Keys that are already
 * pinned are never overwritten here: a differing static key is rejected at
 * handshake time and recorded via [ContactDao.recordPendingKeyChange].
 */
suspend fun ContactDao.updateLearnedKeys(
    contactId: String,
    identityHash: ByteArray,
    ed25519Public: ByteArray,
    x25519StaticPublic: ByteArray,
) {
    val contact = getById(contactId) ?: return
    val learnIdentity = IdentityHashMatcher.isPlaceholderPublicKey(contact.ed25519Public) &&
        !IdentityHashMatcher.isPlaceholderPublicKey(ed25519Public)
    val learnStatic = !contact.hasPinnedStaticKey() &&
        !IdentityHashMatcher.isPlaceholderPublicKey(x25519StaticPublic)
    if (!learnIdentity && !learnStatic) return
    update(
        contact.copy(
            identityHash = if (learnIdentity) identityHash else contact.identityHash,
            ed25519Public = if (learnIdentity) ed25519Public else contact.ed25519Public,
            x25519StaticPublic = if (learnStatic) x25519StaticPublic else contact.x25519StaticPublic,
        ),
    )
}
