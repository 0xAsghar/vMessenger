package ir.vmessenger.domain.usecase.identity

import ir.vmessenger.domain.repository.IdentityRepository
import javax.inject.Inject

class HasIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(): Boolean = identityRepository.hasIdentity()
}
