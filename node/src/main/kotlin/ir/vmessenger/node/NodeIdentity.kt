package ir.vmessenger.node

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The node's persistent identity.
 *
 * [seed] is 32 random bytes stored at `<stateDir>/node.seed` (mode 0600 where
 * the file system supports POSIX permissions). [nodeId] is
 * `SHA-256("vmessenger-node-id" || seed)` and is what the node advertises in
 * the DHT; the seed itself stays private so future node keys can be derived
 * from it without changing the id.
 */
class NodeIdentity private constructor(
    val seed: ByteArray,
    /** True when the seed could not be persisted and lives only for this process. */
    val ephemeral: Boolean,
) {
    val nodeId: ByteArray = deriveNodeId(seed)
    val nodeIdHex: String = nodeId.joinToString("") { "%02x".format(it) }

    companion object {
        const val SEED_FILE_NAME = "node.seed"
        const val SEED_SIZE = 32
        private const val NODE_ID_DOMAIN = "vmessenger-node-id"
        private val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        fun deriveNodeId(seed: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
            update(NODE_ID_DOMAIN.toByteArray(Charsets.UTF_8))
            update(seed)
            digest()
        }

        /**
         * Loads the seed from [stateDir], creating the directory and a fresh
         * seed when missing. Falls back to an ephemeral seed (logged as a
         * warning) when the directory cannot be written.
         */
        fun loadOrCreate(
            stateDir: File,
            log: Logger = LoggerFactory.getLogger(NodeIdentity::class.java),
        ): NodeIdentity {
            val seedFile = File(stateDir, SEED_FILE_NAME)
            readSeed(seedFile, log)?.let { return NodeIdentity(it, ephemeral = false) }
            val seed = ByteArray(SEED_SIZE).also { SecureRandom().nextBytes(it) }
            return try {
                writeSeed(seedFile, seed)
                log.info("Created node identity file={}", seedFile.absolutePath)
                NodeIdentity(seed, ephemeral = false)
            } catch (e: IOException) {
                log.warn(
                    "State dir not writable, using an ephemeral node identity dir={} reason={}",
                    stateDir.absolutePath,
                    e.message,
                )
                NodeIdentity(seed, ephemeral = true)
            }
        }

        private fun readSeed(seedFile: File, log: Logger): ByteArray? {
            if (!seedFile.isFile) return null
            val bytes = try {
                seedFile.readBytes()
            } catch (e: IOException) {
                log.warn("Cannot read node identity file={} reason={}", seedFile.absolutePath, e.message)
                null
            }
            return when {
                bytes == null -> null
                bytes.size == SEED_SIZE -> bytes
                else -> {
                    log.warn("Ignoring corrupt node identity file={} size={}", seedFile.absolutePath, bytes.size)
                    null
                }
            }
        }

        @Throws(IOException::class)
        private fun writeSeed(seedFile: File, seed: ByteArray) {
            val dir = seedFile.absoluteFile.parentFile
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
            val tmp = File(dir, "$SEED_FILE_NAME.tmp")
            tmp.writeBytes(seed)
            restrictPermissions(tmp)
            try {
                Files.move(tmp.toPath(), seedFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), seedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            restrictPermissions(seedFile)
        }

        private fun restrictPermissions(file: File) {
            val path = file.toPath()
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, ownerOnly)
            } else {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            }
        }
    }
}
