package ir.vmessenger.domain.usecase.identity

import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.IdentityRepository
import javax.inject.Inject

class GetIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(): Identity? = identityRepository.getIdentity()
}
