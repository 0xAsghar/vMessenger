package ir.vmessenger.core.designsystem.error

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.format.VmTextFormat
import kotlin.reflect.KClass

/**
 * Every [AppError] that carries no parameters maps straight to a Persian string. Keeping the
 * mapping in a table (instead of a 25-branch `when`) keeps the lookup complexity at one and
 * makes a forgotten error obvious: it falls back to the generic text.
 */
private val ERROR_TEXT: Map<KClass<out AppError>, Int> = mapOf(
    AppError.Unknown::class to R.string.vm_error_unknown,
    AppError.Crypto::class to R.string.vm_error_crypto,
    AppError.Network::class to R.string.vm_error_network,
    AppError.Validation::class to R.string.vm_error_validation,
    AppError.NotFound::class to R.string.vm_error_not_found,
    AppError.Security::class to R.string.vm_error_security,
    AppError.InvalidQr::class to R.string.vm_error_invalid_qr,
    AppError.InvalidUserHash::class to R.string.vm_error_invalid_user_hash,
    AppError.OwnIdentity::class to R.string.vm_error_own_identity,
    AppError.ContactAddFailed::class to R.string.vm_error_contact_add_failed,
    AppError.NoContactSelected::class to R.string.vm_error_no_contact_selected,
    AppError.RequestNotFound::class to R.string.vm_error_request_not_found,
    AppError.ContactNotFound::class to R.string.vm_error_contact_not_found,
    AppError.NoNetwork::class to R.string.vm_error_no_network,
    AppError.SendFailed::class to R.string.vm_error_send_failed,
    AppError.PermissionDenied::class to R.string.vm_error_permission_denied,
    AppError.AttachmentFailed::class to R.string.vm_error_attachment_failed,
    AppError.NotGroupMember::class to R.string.vm_error_not_group_member,
    AppError.GroupClosed::class to R.string.vm_error_group_closed,
    AppError.NotGroupCreator::class to R.string.vm_error_not_group_creator,
    AppError.NoReachableMembers::class to R.string.vm_error_no_reachable_members,
    AppError.UpdateRateLimited::class to R.string.vm_error_update_rate_limited,
    AppError.UpdateChecksumMismatch::class to R.string.vm_error_update_checksum_mismatch,
    AppError.UpdateSignatureMismatch::class to R.string.vm_error_update_signature_mismatch,
    AppError.UpdateNoAsset::class to R.string.vm_error_update_no_asset,
)

/**
 * The Persian sentence shown to the user for this error. `AppError.message` stays English and
 * developer-facing; nothing in the UI should ever render it.
 */
@Composable
fun AppError.toUiText(): String = when (this) {
    is AppError.AttachmentTooLarge -> stringResource(R.string.vm_error_attachment_too_large, digits(maxMb))
    is AppError.GroupFull -> stringResource(R.string.vm_error_group_full, digits(max))
    is AppError.ProtocolVersion -> stringResource(R.string.vm_error_protocol_version, digits(peerMajor))
    else -> stringResource(ERROR_TEXT[this::class] ?: R.string.vm_error_unknown)
}

private fun digits(value: Int): String = VmTextFormat.digits(value.toString())
