package ir.vmessenger.domain.usecase.identity

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.BackupOptions
import ir.vmessenger.domain.repository.IdentityBackupRepository
import javax.inject.Inject

class ExportIdentityBackupUseCase @Inject constructor(
    private val identityBackupRepository: IdentityBackupRepository,
) {
    suspend operator fun invoke(
        passphrase: CharArray,
        options: BackupOptions = BackupOptions(),
    ): AppResult<ByteArray> = identityBackupRepository.exportBundle(passphrase, options)
}
