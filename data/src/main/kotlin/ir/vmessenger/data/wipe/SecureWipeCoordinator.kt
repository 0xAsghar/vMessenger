package ir.vmessenger.data.wipe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.group.GroupSyncTracker
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.RelayDns
import ir.vmessenger.core.crypto.keystore.KeyStoreKeyManager
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.core.database.VMessengerDatabase
import ir.vmessenger.core.database.di.DatabaseModule
import ir.vmessenger.core.datastore.ContactRetryPreferences
import ir.vmessenger.core.datastore.DiscoveryPreferences
import ir.vmessenger.core.datastore.DraftPreferences
import ir.vmessenger.core.datastore.P2PPreferences
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.datastore.SecurityPreferences
import ir.vmessenger.core.datastore.ThemePreferences
import ir.vmessenger.core.notifications.MessageNotificationManager
import ir.vmessenger.core.update.UpdateStore
import ir.vmessenger.data.attachment.AttachmentKeyProvider
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.data.network.LocationServiceControl
import ir.vmessenger.data.network.NetworkCoordinator
import ir.vmessenger.data.network.SelfIdentityCache
import ir.vmessenger.domain.repository.SecureWipeService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess
/**
 * Erases the account from this device: network down, database cleared/closed/
 * deleted, attachments, logs and caches removed, every DataStore emptied,
 * in-memory key caches zeroized and the Keystore master key destroyed — see
 * [SecureWipePlan] for the exact order and why it is that order.
 *
 * Every step is guarded and all of them run, so one failure cannot leave the
 * rest of the data behind. The process is then killed and relaunched through
 * an alarm: Hilt singletons still hold the old SQLCipher passphrase and open
 * network state, and there is no way to rebuild that graph in place.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // One collaborator and one method per wipe stage.
class SecureWipeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkCoordinator: NetworkCoordinator,
    private val locationServiceControl: LocationServiceControl,
    private val messageNotificationManager: MessageNotificationManager,
    private val database: VMessengerDatabase,
    private val securityPreferences: SecurityPreferences,
    private val privacyPreferences: PrivacyPreferences,
    private val p2pPreferences: P2PPreferences,
    private val discoveryPreferences: DiscoveryPreferences,
    private val contactRetryPreferences: ContactRetryPreferences,
    private val themePreferences: ThemePreferences,
    private val updateStore: UpdateStore,
    private val draftPreferences: DraftPreferences,
    private val keyStoreKeyManager: KeyStoreKeyManager,
    private val selfIdentityCache: SelfIdentityCache,
    private val databaseKeyProvider: DatabaseKeyProvider,
    private val attachmentKeyProvider: AttachmentKeyProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SecureWipeService, SecureWipeActions {

    /**
     * Never returns: the last statement is [exitProcess].
     *
     * Runs [NonCancellable] on purpose. The caller is a ViewModel scope that
     * dies with the screen, and a half-done wipe is worse than no wipe: the
     * Keystore master key would be gone while the wrapped passphrase in
     * `SecurityPreferences` survived, leaving every later launch unable to open
     * the database. Once started, the wipe finishes and the process exits.
     */
    override suspend fun wipe() {
        withContext(ioDispatcher + NonCancellable) {
            AppLogger.warn(TAG, "secure wipe started")
            val failed = SecureWipePlan.run(SecureWipePlan.steps(this@SecureWipeCoordinator))
            if (failed.isNotEmpty()) {
                android.util.Log.w(TAG, "secure wipe finished with failed steps: ${failed.joinToString()}")
            }
            scheduleRelaunch()
        }
        exitProcess(0)
    }

    override suspend fun stopNetwork() = networkCoordinator.stop()

    override fun stopLocationSharing() = locationServiceControl.stop()

    override fun cancelNotifications() = messageNotificationManager.cancelAll()

    override suspend fun clearAndCloseDatabase() {
        database.clearAllTables()
        database.close()
    }

    override fun deleteDatabaseFiles() {
        context.deleteDatabase(DatabaseModule.DATABASE_NAME)
        val databaseFile = context.getDatabasePath(DatabaseModule.DATABASE_NAME)
        JOURNAL_SUFFIXES.forEach { suffix -> File(databaseFile.path + suffix).delete() }
        databaseFile.delete()
    }

    override fun deleteFiles() {
        File(context.filesDir, ATTACHMENTS_DIR).deleteRecursively()
        File(context.filesDir, LOGS_DIR).deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    override suspend fun clearPreferences() {
        draftPreferences.clear()
        securityPreferences.clear()
        privacyPreferences.clear()
        p2pPreferences.clear()
        discoveryPreferences.clear()
        themePreferences.clear()
        // Who this device was still dialling, and how hard.
        contactRetryPreferences.clear()
        // The updater's store too: the last-checked stamp, the cached release and the version
        // the user waved away all outlive a wipe otherwise, and they say when this device was
        // last used and which build it was running.
        updateStore.clear()
    }

    override fun resetInMemoryState() {
        selfIdentityCache.clear()
        databaseKeyProvider.reset()
        attachmentKeyProvider.reset()
        NetworkPathTracker.clear()
        GroupSyncTracker.clear()
        P2PConfig.resetToDefaults()
        RelayDns.clearPins()
        AppLogger.clear()
    }

    override fun deleteMasterKey() = keyStoreKeyManager.deleteMasterKey()

    /**
     * Books a launcher start ~300 ms out, then the process dies. `setExact`
     * needs `SCHEDULE_EXACT_ALARM` on Android 12+, which this app does not
     * request, so an inexact alarm is the fallback — a second of delay before
     * the fresh "create identity" screen is acceptable.
     */
    private fun scheduleRelaunch() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (launch == null || alarm == null) return
        val pending = PendingIntent.getActivity(
            context,
            RELAUNCH_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + RELAUNCH_DELAY_MS
        runCatching { alarm.setExact(AlarmManager.RTC, at, pending) }
            .onFailure { runCatching { alarm.set(AlarmManager.RTC, at, pending) } }
    }

    private companion object {
        const val TAG = "Wipe"
        const val ATTACHMENTS_DIR = "attachments"
        const val LOGS_DIR = "logs"
        const val RELAUNCH_REQUEST_CODE = 0x5721
        const val RELAUNCH_DELAY_MS = 300L
        val JOURNAL_SUFFIXES = listOf("-wal", "-shm", "-journal")
    }
}
