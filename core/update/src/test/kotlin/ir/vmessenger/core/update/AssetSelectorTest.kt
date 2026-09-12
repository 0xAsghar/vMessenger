package ir.vmessenger.core.update

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import org.junit.Assert.assertEquals
import org.junit.Test

class AssetSelectorTest {
    private fun asset(name: String) = ReleaseAsset(name, "https://example.invalid/$name", 1_024)

    private val release = listOf(
        asset("vMessenger-1.0.0-arm64-v8a.apk"),
        asset("vMessenger-1.0.0-armeabi-v7a.apk"),
        asset("vMessenger-1.0.0-x86.apk"),
        asset("vMessenger-1.0.0-x86_64.apk"),
        asset("vMessenger-1.0.0-universal.apk"),
        asset("vMessenger-1.0.0-arm64-v8a.apk.sha256"),
        asset("SHA256SUMS.txt"),
        asset("SIGNING.txt"),
    )

    private fun selected(assets: List<ReleaseAsset>, abis: List<String>): String? =
        (AssetSelector.select(assets, abis) as? AppResult.Success)?.data?.name

    @Test
    fun picksTheSplitForThePrimaryAbi() {
        assertEquals(
            "vMessenger-1.0.0-arm64-v8a.apk",
            selected(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi")),
        )
        assertEquals("vMessenger-1.0.0-x86_64.apk", selected(release, listOf("x86_64", "x86")))
    }

    @Test
    fun fallsBackToASecondaryAbiBeforeUniversal() {
        val withoutArm64 = release.filterNot { it.name.contains("-arm64-v8a.apk") }
        assertEquals(
            "vMessenger-1.0.0-armeabi-v7a.apk",
            selected(withoutArm64, listOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun fallsBackToUniversalWhenNoSplitMatches() {
        assertEquals("vMessenger-1.0.0-universal.apk", selected(release, listOf("mips")))
        assertEquals("vMessenger-1.0.0-universal.apk", selected(release, emptyList()))
    }

    @Test
    fun neverPicksAChecksumOrTextAsset() {
        val onlySidecars = release.filterNot { it.name.endsWith(".apk") }
        assertEquals(AppResult.Error(AppError.UpdateNoAsset), AssetSelector.select(onlySidecars, listOf("x86")))
    }

    @Test
    fun releaseWithoutAnyUsableApkIsAnError() {
        assertEquals(AppResult.Error(AppError.UpdateNoAsset), AssetSelector.select(emptyList(), listOf("arm64-v8a")))
    }

    @Test
    fun matchIsAnchoredToTheAbiSuffix() {
        // `-x86.apk` must not be answered by the `-x86_64.apk` build or vice versa.
        val onlyX8664 = listOf(asset("vMessenger-1.0.0-x86_64.apk"))
        assertEquals(AppResult.Error(AppError.UpdateNoAsset), AssetSelector.select(onlyX8664, listOf("x86")))
    }
}
