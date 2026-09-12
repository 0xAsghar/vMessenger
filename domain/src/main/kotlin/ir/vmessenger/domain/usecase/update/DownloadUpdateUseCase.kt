package ir.vmessenger.domain.usecase.update

import ir.vmessenger.domain.model.AvailableUpdate
import ir.vmessenger.domain.model.DownloadProgress
import ir.vmessenger.domain.repository.UpdateRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Downloads and verifies an update; the stream ends in the file to install, or a failure. */
class DownloadUpdateUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
) {
    operator fun invoke(update: AvailableUpdate): Flow<DownloadProgress> = updateRepository.download(update)
}
