package ir.vmessenger.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CopiedAttachment(
    val file: File,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentType: MessageContentType,
    /** SHA-256 of the plaintext (what [file] decrypts to). */
    val sha256: ByteArray,
)

/**
 * The slice of [AttachmentStore] that contact cleanup needs; an interface so
 * the cleanup path is unit-testable without an Android [Context].
 */
interface AttachmentFileStore {
    /**
     * Deletes the attachment at [path]. Only files inside the app's attachment
     * directories are touched — a path that points elsewhere is refused —
     * and a missing file counts as not deleted.
     */
    fun delete(path: String): Boolean
}

/** Read side used by the sender and the UI: opens stored attachments as plaintext streams. */
interface AttachmentContentSource {
    /** Decrypting stream over the encrypted file at [path]; throws when the path or container is invalid. */
    suspend fun openDecrypted(path: String): InputStream
}

/** Write side used by the receiver: sealed per-chunk staging, then an encrypted import. */
interface AttachmentIncomingStore {
    /** A fresh staging area for a [totalSize]-byte transfer in [chunkCount] chunks of [chunkBytes] (last shorter). */
    suspend fun newIncomingStaging(totalSize: Long, chunkCount: Int, chunkBytes: Int): IncomingStaging

    /**
     * Reads [staging] back chunk by chunk, verifies the plaintext SHA-256 against
     * [expectedSha256] and encrypts it into the incoming directory under
     * [fileName]. Returns the stored file, or null on a digest mismatch; throws
     * when the import itself fails. The staging is discarded either way.
     */
    suspend fun importStaged(staging: IncomingStaging, fileName: String, expectedSha256: ByteArray): File?
}

/**
 * App-private, encrypted-at-rest storage for chat attachments. Outgoing picks are
 * encrypted into files/attachments/out (content Uris are not durable); incoming
 * transfers are staged in cacheDir/attachments-in as independently sealed chunks
 * ([SealedChunkStaging]) and re-encrypted into files/attachments/in on completion,
 * so not even a partial transfer sits on disk in the clear. Viewing goes through
 * [exportForViewing], which decrypts into cacheDir/attachments-view (purged at
 * start and 10 min after export) so `ACTION_VIEW` never sees the encrypted container.
 */
@Singleton
@Suppress("TooManyFunctions") // one store, three roles (delete / read / import) plus the two copy paths
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: AttachmentCrypto,
    private val cryptoEngine: CryptoEngine,
    private val keyProvider: AttachmentKeyProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AttachmentFileStore, AttachmentContentSource, AttachmentIncomingStore {
    private val outDir = File(context.filesDir, "attachments/out").apply { mkdirs() }
    private val inDir = File(context.filesDir, "attachments/in").apply { mkdirs() }
    private val stagingDir = File(context.cacheDir, "attachments-in").apply { mkdirs() }
    private val viewDir = File(context.cacheDir, VIEW_DIR).apply { mkdirs() }
    private val roots by lazy { listOf(outDir, inDir).map { it.canonicalFile } }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler("Attachment"))

    init {
        // Anything left from a previous process is scratch (plaintext view copies,
        // sealed partial transfers whose keys died with the process): drop it.
        scope.launch { purgeTransient() }
    }

    override fun delete(path: String): Boolean {
        val file = insideRoots(path) ?: return false
        return file.delete()
    }

    suspend fun copyFromUri(sourceUri: String): CopiedAttachment = withContext(ioDispatcher) {
        val uri = Uri.parse(sourceUri)
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: DEFAULT_MIME
        val displayName = queryDisplayName(uri) ?: fallbackName(mimeType)
        val target = File(outDir, "${UUID.randomUUID()}-${sanitize(displayName)}")
        val input = resolver.openInputStream(uri) ?: error("cannot open $sourceUri")
        val (size, digest) = input.use { encryptInto(it, target) }
        CopiedAttachment(
            file = target,
            fileName = displayName,
            mimeType = mimeType,
            sizeBytes = size,
            contentType = contentTypeFor(mimeType),
            sha256 = digest,
        )
    }

    override suspend fun newIncomingStaging(totalSize: Long, chunkCount: Int, chunkBytes: Int): IncomingStaging =
        SealedChunkStaging(
            cryptoEngine = cryptoEngine,
            masterKey = keyProvider.get(),
            file = File(stagingDir, "${UUID.randomUUID()}.part"),
            totalSize = totalSize,
            chunkCount = chunkCount,
            chunkBytes = chunkBytes,
            ioDispatcher = ioDispatcher,
        )

    override suspend fun importStaged(
        staging: IncomingStaging,
        fileName: String,
        expectedSha256: ByteArray,
    ): File? = withContext(ioDispatcher) {
        val sealed = requireNotNull(staging as? SealedChunkStaging) { "staging does not belong to this store" }
        val target = File(inDir, "${UUID.randomUUID()}-${sanitize(fileName.ifBlank { "attachment" })}")
        try {
            // The digest is taken over the decrypted stream as it is re-encrypted: no plaintext copy.
            val (_, digest) = sealed.openPlaintext().use { encryptInto(it, target) }
            if (digest.contentEquals(expectedSha256)) {
                target
            } else {
                target.delete()
                null
            }
        } finally {
            sealed.discard()
        }
    }

    override suspend fun openDecrypted(path: String): InputStream {
        val file = insideRoots(path) ?: throw IllegalArgumentException("attachment path outside store")
        val key = keyProvider.get()
        return withContext(ioDispatcher) { crypto.decrypt(key, file.inputStream().buffered()) }
    }

    /**
     * Decrypts the attachment at [path] into the view cache as `<uuid>-<fileName>` and
     * returns that plaintext file (deleted again after [VIEW_TTL_MS]).
     */
    suspend fun exportForViewing(path: String, fileName: String): File = withContext(ioDispatcher) {
        val target = File(viewDir, "${UUID.randomUUID()}-${sanitize(fileName.ifBlank { "attachment" })}")
        openDecrypted(path).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        scope.launch {
            delay(VIEW_TTL_MS)
            target.delete()
        }
        target
    }

    /** Encrypts [input] into [target], enforcing [MAX_ATTACHMENT_BYTES]; returns plaintext size + SHA-256. */
    private suspend fun encryptInto(input: InputStream, target: File): Pair<Long, ByteArray> {
        val key = keyProvider.get()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val ok = runCatching {
            crypto.encrypt(key, target.outputStream().buffered()).use { sink ->
                size = DigestInputStream(input, digest).copyBounded(sink)
            }
        }
        if (ok.isFailure || size !in 1..MAX_ATTACHMENT_BYTES) {
            target.delete()
            ok.exceptionOrNull()?.let { throw it }
            error("attachment too large (${size / MEGABYTE} MB, max ${MAX_ATTACHMENT_BYTES / MEGABYTE} MB)")
        }
        return size to digest.digest()
    }

    /** Copies until EOF or just past the size cap (so the caller can reject without reading everything). */
    private fun InputStream.copyBounded(output: OutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (total <= MAX_ATTACHMENT_BYTES) {
            val n = read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
        }
        return total
    }

    private fun insideRoots(path: String): File? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val inside = roots.any { root -> file.startsWith(root) && file != root }
        return file.takeIf { inside }
    }

    private fun purgeTransient() {
        var removed = 0
        for (dir in listOf(viewDir, stagingDir)) {
            dir.listFiles()?.forEach { if (it.delete()) removed++ }
        }
        if (removed > 0) AppLogger.info("Attachment", "purged $removed transient file(s)")
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }

    private fun fallbackName(mimeType: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
        return "attachment.$ext"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\u0600-\\u06FF-]"), "_").take(MAX_NAME_LENGTH)

    companion object {
        const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
        const val VIEW_DIR = "attachments-view"
        private const val VIEW_TTL_MS = 10 * 60_000L
        private const val COPY_BUFFER = 64 * 1024
        private const val MEGABYTE = 1024 * 1024
        private const val MAX_NAME_LENGTH = 80
        private const val DEFAULT_MIME = "application/octet-stream"

        fun contentTypeFor(mimeType: String): MessageContentType = when {
            mimeType.startsWith("image/") -> MessageContentType.IMAGE
            mimeType.startsWith("video/") -> MessageContentType.VIDEO
            else -> MessageContentType.FILE
        }
    }
}
