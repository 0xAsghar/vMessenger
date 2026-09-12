package ir.vmessenger.core.update

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.update.di.UpdatesDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform side of applying an update: may this build install an APK, what
 * Uri does the package installer want, which key was the running app signed
 * with, and where does the user's "save a copy" go.
 *
 * An interface so the repository's decisions stay testable without a device.
 */
interface UpdateInstaller {
    fun canInstallPackages(): Boolean

    /** A `FileProvider` content Uri for [path], or null when the file is gone. */
    fun installUri(path: String): String?

    fun installedSignerDigestHex(): String?

    suspend fun copyTo(path: String, destinationUri: String): AppResult<Unit>
}

@Singleton
class AndroidUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    @UpdatesDir private val directory: File,
    private val signingInfoReader: SigningInfoReader,
) : UpdateInstaller {
    override fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun installUri(path: String): String? = runCatching {
        // Only files this module wrote are ever shared: the provider exposes all
        // of `cacheDir/updates`, and `path` arrives from the caller.
        val file = File(path).takeIf { it.isFile && it.parentFile?.canonicalPath == directory.canonicalPath }
        file?.let { FileProvider.getUriForFile(context, authority(), it).toString() }
    }.getOrNull()

    override fun installedSignerDigestHex(): String? = signingInfoReader.hexOrNull()

    override suspend fun copyTo(path: String, destinationUri: String): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { write(File(path), destinationUri) }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Error(AppError.Unknown(it.message ?: "saving the update failed")) },
            )
        }

    private fun write(source: File, destinationUri: String) {
        val output = context.contentResolver.openOutputStream(Uri.parse(destinationUri))
            ?: throw IOException("the chosen document cannot be written")
        output.use { target -> source.inputStream().use { it.copyTo(target) } }
    }

    /** Matches `${applicationId}.fileprovider` in the app manifest. */
    private fun authority(): String = context.packageName + FILE_PROVIDER_SUFFIX

    private companion object {
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }
}
