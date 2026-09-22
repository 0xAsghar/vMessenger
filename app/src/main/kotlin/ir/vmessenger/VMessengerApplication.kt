package ir.vmessenger

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import ir.vmessenger.app.locale.AppLocaleController
import ir.vmessenger.app.network.NetworkKeepAliveWorker
import ir.vmessenger.app.network.startNetworkService
import ir.vmessenger.app.work.ExpiryPurgeWorker
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.core.database.DatabaseKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class VMessengerApplication : Application(), Configuration.Provider {
    @Inject lateinit var databaseKeyProvider: DatabaseKeyProvider

    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + loggingExceptionHandler(TAG),
    )

    private lateinit var fileLogSink: FileLogSink

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    @Inject
    lateinit var appLocaleController: AppLocaleController

    override fun onCreate() {
        System.loadLibrary("sqlcipher")
        super.onCreate()
        // Debug builds may store/dial ws:// or host:port nodes on local hosts (emulator, LAN); release: wss:// only.
        NodeAddressPolicy.current = NodeAddressPolicy(allowInsecureLocal = BuildConfig.DEBUG)
        // Before anything formats a number or builds a notification: see AppLocaleController.
        appLocaleController.sync()
        MapLibre.getInstance(this)
        fileLogSink = FileLogSink(this)
        AppLogger.addSink(fileLogSink)
        if (BuildConfig.DEBUG) {
            AppLogger.addSink(LogcatSink())
        }
        AppLogger.info(TAG, "vMessenger started")
        startKeepAliveWork()
        startExpiryPurgeWork()
        // The passphrase is unwrapped from the Keystore, which can take hundreds
        // of milliseconds on first use — never on the main thread. The network
        // service is started only once a key exists, since everything it does
        // needs the database open.
        applicationScope.launch {
            val keyReady = runCatching { databaseKeyProvider.initialize() }
                .onFailure { AppLogger.error(TAG, "database key init failed, service not started: $it") }
                .isSuccess
            if (keyReady) {
                withContext(Dispatchers.Main) {
                    startNetworkService(this@VMessengerApplication, reason = "app start")
                }
            }
        }
    }

    private fun startKeepAliveWork() {
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                NetworkKeepAliveWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                NetworkKeepAliveWorker.periodicRequest(),
            )
        }.onFailure { AppLogger.warn(TAG, "keep-alive work not enqueued: $it") }
    }

    private fun startExpiryPurgeWork() {
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                ExpiryPurgeWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                ExpiryPurgeWorker.periodicRequest(),
            )
        }.onFailure { AppLogger.warn(TAG, "expiry purge work not enqueued: $it") }
    }

    private companion object {
        const val TAG = "App"
    }
}
