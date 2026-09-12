package ir.vmessenger.domain.usecase.update

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.UpdateRepository
import javax.inject.Inject

/**
 * Hands the verified APK to a document the user picked. Needed when the release is
 * signed by a different key than the installed app: Android refuses that upgrade, so
 * the honest path is to let the user keep the file, uninstall, and install by hand.
 */
class SaveUpdateApkUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
) {
    suspend operator fun invoke(path: String, destinationUri: String): AppResult<Unit> =
        updateRepository.saveTo(path, destinationUri)
}
