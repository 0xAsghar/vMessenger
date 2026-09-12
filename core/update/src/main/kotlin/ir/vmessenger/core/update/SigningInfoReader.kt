package ir.vmessenger.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** `== vMessenger-1.0.0-arm64-v8a.apk` — one block per APK in the release's `SIGNING.txt`. */
private val BLOCK_HEADER = Regex("^==\\s*(\\S.*)$")

/** The line `apksigner verify --print-certs` prints for the APK's own signer. */
private val SIGNER_DIGEST = Regex("^Signer #1 certificate SHA-256 digest:\\s*([0-9a-fA-F:]+)$")

/**
 * The certificate digest the running app was installed with.
 *
 * An interface because everything else here is pure text, and a unit test cannot
 * reach a [PackageManager]; [SigningInfoReader] is the on-device implementation.
 */
fun interface InstalledSignerDigest {
    fun hexOrNull(): String?
}

/** Parses a release's `SIGNING.txt`. Pure, so the format is covered by unit tests. */
object SigningInfo {
    fun parse(content: String): Map<String, String> {
        val digests = LinkedHashMap<String, String>()
        var asset = ""
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            val header = BLOCK_HEADER.matchEntire(line)?.groupValues?.get(1)?.trim()
            val digest = SIGNER_DIGEST.matchEntire(line)?.groupValues?.get(1)
            if (header != null) asset = header
            if (digest != null) digests[asset] = normalize(digest)
        }
        return digests
    }

    /**
     * The signer of [assetName], falling back to the first block: the release
     * workflow already fails when the ABI splits are not all signed by one key,
     * so any block answers the question "who signed this release".
     */
    fun digestFor(content: String, assetName: String): String? {
        val digests = parse(content)
        return digests[assetName] ?: digests.values.firstOrNull()
    }

    /**
     * Fail-closed: an absent or blank digest on either side never matches, so a
     * release that published no `SIGNING.txt` cannot pass verification.
     */
    fun matches(left: String?, right: String?): Boolean {
        val expected = left?.let(::normalize).orEmpty()
        return expected.isNotEmpty() && expected == right?.let(::normalize).orEmpty()
    }

    /** Hex compares case-insensitively, and `:`-separated digests are the same value. */
    private fun normalize(value: String): String = value.filterNot { it == ':' || it.isWhitespace() }.lowercase()
}

@Singleton
class SigningInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledSignerDigest {
    /** SHA-256 over the DER certificate, which is exactly what `apksigner --print-certs` prints. */
    override fun hexOrNull(): String? = runCatching {
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) modern() else legacy()
        certificate?.let { MessageDigest.getInstance("SHA-256").digest(it).toHexLowercase() }
    }.getOrNull()

    /**
     * `apkContentsSigners` (not `signingCertificateHistory`) is the signer of the
     * APK as installed, which is the value `SIGNING.txt` records.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun modern(): ByteArray? = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        .signingInfo
        ?.apkContentsSigners
        ?.firstOrNull()
        ?.toByteArray()

    /** minSdk is 26, so API 26/27 are still reachable and `GET_SIGNATURES` is all they have. */
    @Suppress("DEPRECATION")
    private fun legacy(): ByteArray? = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        .signatures
        ?.firstOrNull()
        ?.toByteArray()
}
