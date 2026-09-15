package ir.vmessenger.app.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.DatabaseKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Brings the network foreground service back after a reboot so a paired peer can
 * reach this device without the user opening the app.
 *
 * The database passphrase is unwrapped first, inside this receiver's `goAsync`
 * window: the service injects `NetworkCoordinator` (and with it the database) in
 * `onCreate`, on the main thread, so a cold boot would otherwise do the Keystore
 * work there. When the key cannot be produced at all the service is not started
 * — it could only crash on its first database access.
 *
 * Deliberately not direct-boot aware: the Keystore is only available after the
 * first unlock, so nothing useful can happen before `BOOT_COMPLETED`.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    /** Hilt entry point rather than `@AndroidEntryPoint`: a receiver cannot call the generated `super.onReceive`. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface KeyProviderEntryPoint {
        fun databaseKeyProvider(): DatabaseKeyProvider
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AppLogger.info(TAG, "boot completed, starting network service")
        val appContext = context.applicationContext
        val keyProvider = EntryPointAccessors
            .fromApplication(appContext, KeyProviderEntryPoint::class.java)
            .databaseKeyProvider()
        val pendingResult = goAsync()
        scope.launch {
            try {
                runCatching {
                    keyProvider.initialize()
                    // initialize() returns normally for a *locked* provider — loading is exactly
                    // what it must not do — so "did not throw" is not "the key is ready". Without
                    // this the receiver starts the service, which injects the database, which
                    // throws in onCreate.
                    check(!keyProvider.isLocked) { "the app lock is holding the database shut" }
                }
                    .onSuccess { startNetworkService(appContext, reason = "boot") }
                    .onFailure { AppLogger.error(TAG, "database key init failed, service not started: $it") }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Boot"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingExceptionHandler(TAG))
    }
}
