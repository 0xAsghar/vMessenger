package ir.vmessenger.domain.usecase.update

import ir.vmessenger.domain.repository.UpdateRepository
import javax.inject.Inject

/** "Not this one": stops offering [versionName] until something newer is released. */
class SkipUpdateVersionUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
) {
    suspend operator fun invoke(versionName: String) = updateRepository.skip(versionName)
}
