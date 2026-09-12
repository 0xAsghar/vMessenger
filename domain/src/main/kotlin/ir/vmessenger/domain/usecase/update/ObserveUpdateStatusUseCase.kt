package ir.vmessenger.domain.usecase.update

import ir.vmessenger.domain.model.UpdateStatus
import ir.vmessenger.domain.repository.UpdateRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** When the last check happened and which version the user waved away. */
class ObserveUpdateStatusUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
) {
    operator fun invoke(): Flow<UpdateStatus> = updateRepository.observeStatus()
}
