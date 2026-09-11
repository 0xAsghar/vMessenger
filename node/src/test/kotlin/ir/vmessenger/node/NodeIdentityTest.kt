package ir.vmessenger.node

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

class NodeIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val log = LoggerFactory.getLogger(NodeIdentityTest::class.java)

    @Test
    fun `creates a seed file and derives the node id from it`() {
        val stateDir = File(tmp.root, "state") // does not exist yet
        val identity = NodeIdentity.loadOrCreate(stateDir, log)

        val seedFile = File(stateDir, NodeIdentity.SEED_FILE_NAME)
        assertTrue(seedFile.isFile)
        assertEquals(NodeIdentity.SEED_SIZE, seedFile.length().toInt())
        assertArrayEquals(seedFile.readBytes(), identity.seed)
        assertFalse(identity.ephemeral)

        val expected = MessageDigest.getInstance("SHA-256").run {
            update("vmessenger-node-id".toByteArray())
            update(identity.seed)
            digest()
        }
        assertArrayEquals(expected, identity.nodeId)
        assertEquals(64, identity.nodeIdHex.length)
    }

    @Test
    fun `identity is stable across loads`() {
        val stateDir = tmp.newFolder("state")
        val first = NodeIdentity.loadOrCreate(stateDir, log)
        val second = NodeIdentity.loadOrCreate(stateDir, log)

        assertArrayEquals(first.seed, second.seed)
        assertArrayEquals(first.nodeId, second.nodeId)
    }

    @Test
    fun `seed file is owner-only`() {
        val stateDir = tmp.newFolder("state")
        NodeIdentity.loadOrCreate(stateDir, log)
        val path = File(stateDir, NodeIdentity.SEED_FILE_NAME).toPath()
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )
    }

    @Test
    fun `corrupt seed file is replaced`() {
        val stateDir = tmp.newFolder("state")
        File(stateDir, NodeIdentity.SEED_FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))

        val identity = NodeIdentity.loadOrCreate(stateDir, log)
        assertFalse(identity.ephemeral)
        assertEquals(NodeIdentity.SEED_SIZE, File(stateDir, NodeIdentity.SEED_FILE_NAME).length().toInt())
        assertArrayEquals(identity.seed, NodeIdentity.loadOrCreate(stateDir, log).seed)
    }

    @Test
    fun `falls back to an ephemeral identity when the dir cannot be created`() {
        val blocker = tmp.newFile("not-a-dir")
        val stateDir = File(blocker, "state") // parent is a regular file, mkdirs must fail

        val identity = NodeIdentity.loadOrCreate(stateDir, log)
        assertTrue(identity.ephemeral)
        assertEquals(NodeIdentity.SEED_SIZE, identity.seed.size)
        assertFalse(File(stateDir, NodeIdentity.SEED_FILE_NAME).exists())

        // Each ephemeral identity is fresh.
        val again = NodeIdentity.loadOrCreate(stateDir, log)
        assertFalse(identity.seed.contentEquals(again.seed))
    }
}
