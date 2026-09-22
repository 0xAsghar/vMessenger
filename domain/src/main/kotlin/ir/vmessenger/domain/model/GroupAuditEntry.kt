package ir.vmessenger.domain.model

/**
 * One thing a group message said before its sender revised it.
 *
 * Read only on the reviewing device, from what that device itself captured while retention was on.
 * Nothing here is fetched from another member: a group message reaches every member, so an admin's
 * own device already holds the revisions it saw, and asking someone else's device to serve them
 * would be a larger privacy surface for no practical gain.
 */
data class GroupAuditEntry(
    val messageId: String,
    /** Lowercase hex identity hash of whoever wrote it; null when it was our own message. */
    val authorIdentityHash: String?,
    /** The name this device knows for the author, if any. */
    val authorName: String?,
    val deleted: Boolean,
    /** What it said. Either a body or an attachment caption; never both. */
    val text: String?,
    val attachmentName: String?,
    val capturedAtUnixMs: Long,
)
