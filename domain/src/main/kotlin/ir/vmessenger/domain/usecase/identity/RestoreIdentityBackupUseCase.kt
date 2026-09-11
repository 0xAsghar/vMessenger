package ir.vmessenger.domain.usecase.identity

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.RestoreSummary
import ir.vmessenger.domain.repository.IdentityBackupRepository
import javax.inject.Inject

class RestoreIdentityBackupUseCase @Inject constructor(
    private val identityBackupRepository: IdentityBackupRepository,
) {
    suspend operator fun invoke(bytes: ByteArray, passphrase: CharArray): AppResult<RestoreSummary> =
        identityBackupRepository.importBundle(bytes, passphrase)
}
