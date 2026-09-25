package ir.vmessenger.data.nodesetup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.nodesetup.BundleFile
import ir.vmessenger.core.nodesetup.InstallerBundle
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The node installer the APK carries (`assets/node-installer/`, built by `:app:bundleNodeInstaller`).
 * Its SHA256SUMS lists every file, so the file list comes from there; the checksums file itself is
 * uploaded too, for the server's own `sha256sum -c`.
 */
@Singleton
class AssetInstallerBundle @Inject constructor(@ApplicationContext private val context: Context) : InstallerBundle {

    private val manifest: String by lazy { read(MANIFEST).decodeToString() }

    override val nodeVersion: String by lazy {
        Regex("\"nodeVersion\"\\s*:\\s*\"([^\"]+)\"").find(manifest)?.groupValues?.get(1).orEmpty()
    }

    override val protocol: Int by lazy {
        Regex("\"protocol\"\\s*:\\s*(\\d+)").find(manifest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    override val files: List<BundleFile> by lazy {
        val sums = read(SUMS)
        val listed = sums.decodeToString().lines().mapNotNull { line ->
            line.split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 }?.let { (sha, path) ->
                BundleFile(path.trim(), sha, sizeOf(path.trim())) { open(path.trim()) }
            }
        }
        listed + BundleFile(SUMS, sha256(sums), sums.size.toLong()) { sums.inputStream() }
    }

    private fun open(path: String): InputStream = context.assets.open("$DIR/$path")

    private fun read(path: String): ByteArray = open(path).use { it.readBytes() }

    /** The tarball is stored uncompressed, so its length is known; small files are just counted. */
    private fun sizeOf(path: String): Long =
        runCatching { context.assets.openFd("$DIR/$path").use { it.length } }
            .getOrElse { open(path).use { input -> input.skip(Long.MAX_VALUE) } }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIR = "node-installer"
        const val MANIFEST = "manifest.json"
        const val SUMS = "SHA256SUMS"
    }
}
