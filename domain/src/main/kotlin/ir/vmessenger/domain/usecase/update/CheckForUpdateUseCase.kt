package ir.vmessenger.domain.usecase.update

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.UpdateCheck
import ir.vmessenger.domain.repository.UpdateRepository
import javax.inject.Inject

/**
 * Looks for a newer release. [force] is what the user's own "check now" passes;
 * anything automatic leaves it false and is throttled.
 */
class CheckForUpdateUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
) {
    suspend operator fun invoke(force: Boolean = false): AppResult<UpdateCheck> = updateRepository.check(force)
}
