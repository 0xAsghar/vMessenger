package ir.vmessenger.core.update

import android.os.Build
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult

/** One file attached to a GitHub release, flattened out of the release JSON. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * Picks the one APK of a release this device can install.
 *
 * The release workflow publishes `vMessenger-<version>-<abi>.apk` per ABI plus a
 * `-universal.apk`; the split build is a third of the size, so it is preferred
 * and `universal` is the fallback.
 */
object AssetSelector {
    const val UNIVERSAL = "universal"
    private const val APK_SUFFIX = ".apk"

    /**
     * [abis] is a parameter rather than a read of [Build.SUPPORTED_ABIS] so the
     * choice is testable off-device; pass [deviceAbis] in production.
     */
    fun select(assets: List<ReleaseAsset>, abis: List<String>): AppResult<ReleaseAsset> {
        // Every supported ABI is tried in the device's own preference order before
        // `universal`: a 64-bit device also runs the armeabi-v7a build, and half a
        // release is better than none.
        val match = (abis + UNIVERSAL).firstNotNullOfOrNull { abi -> assets.firstOrNull { it.matches(abi) } }
        return if (match == null) AppResult.Error(AppError.UpdateNoAsset) else AppResult.Success(match)
    }

    /** Null-safe because the stubbed `android.jar` of a unit test leaves this field null. */
    fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()

    private fun ReleaseAsset.matches(abi: String): Boolean =
        name.endsWith("-$abi$APK_SUFFIX", ignoreCase = true)
}
