package ir.vmessenger.domain.usecase.settings

import ir.vmessenger.domain.repository.SecureWipeService
import javax.inject.Inject

/**
 * Wipes identity, messages, attachments, logs and every preference, then
 * relaunches the app at the "create identity" screen. Destructive and
 * irreversible; callers must confirm with the user first.
 */
class SecureWipeUseCase @Inject constructor(
    private val secureWipeService: SecureWipeService,
) {
    suspend operator fun invoke() = secureWipeService.wipe()
}
