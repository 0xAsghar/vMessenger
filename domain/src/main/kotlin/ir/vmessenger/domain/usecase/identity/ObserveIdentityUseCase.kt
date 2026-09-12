package ir.vmessenger.domain.usecase.identity

import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** This device's identity as it changes (a rename, a restore, a wipe); null when there is none. */
class ObserveIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    operator fun invoke(): Flow<Identity?> = identityRepository.observeIdentity()
}
