package ir.vmessenger.data.cleanup

import java.io.File

/**
 * Deletes what the in-app updater (removed after 2.0.0-beta.1) left on an install that ran it:
 * its DataStore file — the last-checked stamp, the cached release and the version the user
 * waved away — and the APKs it downloaded into `cacheDir/updates`.
 *
 * Idempotent and cheap once there is nothing left, so it runs on every start as well as from
 * the secure wipe. Blocking file I/O: call it off the main thread.
 */
object LegacyUpdaterCleanup {

    /** True when something was found and deleted. */
    fun run(filesDir: File, cacheDir: File): Boolean {
        val store = File(filesDir, DATASTORE_DIR)
        val leftovers = listOf(
            File(store, STORE_FILE),
            File(store, STORE_FILE + TMP_SUFFIX),
            File(cacheDir, DOWNLOADS_DIR),
        ).filter { it.exists() }
        leftovers.forEach { it.deleteRecursively() }
        return leftovers.isNotEmpty()
    }

    private const val DATASTORE_DIR = "datastore"
    private const val STORE_FILE = "vmessenger_update.preferences_pb"
    private const val TMP_SUFFIX = ".tmp"
    private const val DOWNLOADS_DIR = "updates"
}
