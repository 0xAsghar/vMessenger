package ir.vmessenger.domain.usecase.identity

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.IdentityRepository
import javax.inject.Inject

class UpdateDisplayNameUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(displayName: String): AppResult<Unit> =
        identityRepository.updateDisplayName(displayName)
}
