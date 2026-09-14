package ir.vmessenger.domain.usecase.identity

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.repository.ProfileBroadcaster
import javax.inject.Inject

/**
 * Renames this identity and tells everyone who already has it.
 *
 * The telling is the point. Until there was a profile envelope, a display name reached a peer
 * exactly once, inside the pairing handshake, so renaming yourself was invisible to every existing
 * contact forever — they went on seeing whatever you were called the day you met.
 */
class UpdateDisplayNameUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val profileBroadcaster: ProfileBroadcaster,
) {
    suspend operator fun invoke(displayName: String): AppResult<Unit> {
        val result = identityRepository.updateDisplayName(displayName)
        // Only on success, and after the local write: the broadcast reads the stored identity.
        if (result is AppResult.Success) profileBroadcaster.broadcastProfile()
        return result
    }
}
