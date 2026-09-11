package ir.vmessenger.domain.usecase.identity

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.BackupHeaderInfo
import ir.vmessenger.domain.repository.IdentityBackupRepository
import javax.inject.Inject

class InspectBackupUseCase @Inject constructor(
    private val identityBackupRepository: IdentityBackupRepository,
) {
    suspend operator fun invoke(bytes: ByteArray): AppResult<BackupHeaderInfo> =
        identityBackupRepository.inspectBundle(bytes)
}
