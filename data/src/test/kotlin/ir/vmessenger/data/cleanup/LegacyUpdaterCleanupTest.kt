package ir.vmessenger.data.cleanup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyUpdaterCleanupTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `deletes the updater's store and downloads and nothing else`() {
        val files = folder.newFolder("files")
        val cache = folder.newFolder("cache")
        val store = File(files, "datastore").apply { mkdirs() }
        val updaterStore = File(store, "vmessenger_update.preferences_pb").apply { writeText("x") }
        val updaterTmp = File(store, "vmessenger_update.preferences_pb.tmp").apply { writeText("x") }
        val otherStore = File(store, "vmessenger_security.preferences_pb").apply { writeText("keep") }
        val downloads = File(cache, "updates").apply { mkdirs() }
        File(downloads, "vMessenger-2.0.0-beta.1-arm64-v8a.apk.part").writeText("apk")
        val otherCache = File(cache, "attachments_view").apply { mkdirs() }

        assertTrue(LegacyUpdaterCleanup.run(files, cache))

        assertFalse(updaterStore.exists())
        assertFalse(updaterTmp.exists())
        assertFalse(downloads.exists())
        assertTrue(otherStore.exists())
        assertTrue(otherCache.exists())
    }

    @Test
    fun `a second run finds nothing`() {
        val files = folder.newFolder("files")
        val cache = folder.newFolder("cache")
        File(cache, "updates").mkdirs()

        assertTrue(LegacyUpdaterCleanup.run(files, cache))
        assertFalse(LegacyUpdaterCleanup.run(files, cache))
    }

    @Test
    fun `missing directories are not an error`() {
        assertFalse(LegacyUpdaterCleanup.run(File(folder.root, "no-files"), File(folder.root, "no-cache")))
    }
}
