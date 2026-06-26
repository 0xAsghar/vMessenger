package ir.vmessenger.data.repository

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity

suspend fun ContactDao.findByIdentityHash(identityHash: ByteArray): ContactEntity? {
    getByIdentityHash(identityHash)?.let { return it }
    return getAll().firstOrNull { IdentityHashMatcher.matches(it.identityHash, identityHash) }
}

suspend fun ContactDao.updateLearnedKeys(
    contactId: String,
    identityHash: ByteArray,
    ed25519Public: ByteArray,
    x25519StaticPublic: ByteArray,
) {
    val contact = getById(contactId) ?: return
    if (
        contact.identityHash.contentEquals(identityHash) &&
        contact.ed25519Public.contentEquals(ed25519Public) &&
        contact.x25519StaticPublic?.contentEquals(x25519StaticPublic) == true
    ) {
        return
    }
    update(
        contact.copy(
            identityHash = identityHash,
            ed25519Public = ed25519Public,
            x25519StaticPublic = x25519StaticPublic,
        ),
    )
}
