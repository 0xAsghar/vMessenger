package ir.vmessenger.core.common

/**
 * Every failure the domain and data layers can surface.
 *
 * [message] is developer-facing (logs, crash reports) and is deliberately English; the
 * user-facing Persian text lives in `core:designsystem` (`AppError.toUiText()`), so no
 * subtype should ever carry a translated string.
 */
sealed class AppError(open val message: String) {
    data class Unknown(override val message: String) : AppError(message)
    data class Crypto(override val message: String) : AppError(message)
    data class Network(override val message: String) : AppError(message)
    data class Validation(override val message: String) : AppError(message)
    data class NotFound(override val message: String) : AppError(message)
    data class Security(override val message: String) : AppError(message)

    /** The peer speaks a different protocol major ([peerMajor]); the user must update one of the apps. */
    data class ProtocolVersion(override val message: String, val peerMajor: Int) : AppError(message)

    // Pairing / contacts ------------------------------------------------------
    data object InvalidQr : AppError("pairing payload is not a valid vMessenger QR")
    data object InvalidUserHash : AppError("user hash is malformed")
    data object ContactAddFailed : AppError("adding the contact failed")
    data object NoContactSelected : AppError("no contact selected")
    data object RequestNotFound : AppError("contact request no longer exists")
    data object ContactNotFound : AppError("contact no longer exists")

    // Messaging ---------------------------------------------------------------
    data object NoNetwork : AppError("no usable network path")
    data object SendFailed : AppError("sending the message failed")
    data object PermissionDenied : AppError("a required runtime permission was denied")

    /** The picked attachment is larger than [maxMb] megabytes. */
    data class AttachmentTooLarge(val maxMb: Int) : AppError("attachment exceeds $maxMb MB")
    data object AttachmentFailed : AppError("attachment transfer failed")

    // Groups ------------------------------------------------------------------
    /** The group already holds [max] members. */
    data class GroupFull(val max: Int) : AppError("group already has $max members")
    data object NotGroupMember : AppError("sender is not an active member of the group")
    data object GroupClosed : AppError("the group is closed")
    data object NoReachableMembers : AppError("no approved, reachable group members")

    // Updater -----------------------------------------------------------------
    data object UpdateRateLimited : AppError("release API rate limit reached")
    data object UpdateChecksumMismatch : AppError("downloaded artifact checksum mismatch")
    data object UpdateSignatureMismatch : AppError("release signer differs from the installed one")
    data object UpdateNoAsset : AppError("release carries no asset for this ABI")
}
