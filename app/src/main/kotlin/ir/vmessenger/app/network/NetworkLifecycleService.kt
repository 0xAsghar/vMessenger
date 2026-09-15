package ir.vmessenger.app.network

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.core.notifications.NetworkNotificationManager
import ir.vmessenger.data.lock.AppLockCoordinator
import ir.vmessenger.data.lock.LockState
import ir.vmessenger.data.network.NetworkCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class NetworkLifecycleService : Service() {
    /**
     * Provider, not the coordinator: resolving it builds the database, and this service is
     * restarted by the system (it returns `START_STICKY`) at moments nobody chose — including
     * while the strict app lock is holding the passphrase behind an authentication. Field
     * injection happens before `onCreate` runs, so taking it by value crashed the service
     * during creation, and `START_STICKY` then restarted it into the same crash, forever.
     */
    @Inject
    lateinit var networkCoordinator: Provider<NetworkCoordinator>

    @Inject
    lateinit var networkNotificationManager: NetworkNotificationManager

    @Inject
    lateinit var databaseKeyProvider: DatabaseKeyProvider

    /**
     * Lazy like the coordinator, and for the same reason — though this one only reaches the
     * database through providers of its own, so resolving it does not open anything.
     */
    @Inject
    lateinit var appLock: Provider<AppLockCoordinator>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether `startForeground()` has run; see [enterForeground]. */
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // Strict mode promises that nothing is delivered while the app is locked, and the settings
        // screen says so in as many words. Closing the database does not deliver on that by
        // itself: Room is already holding the key inside its open connection, so the outbox and
        // the relay simply reopen it seconds later. This is what makes the promise true — the
        // stack goes down with the lock, and MainViewModel brings it back on the unlock.
        scope.launch {
            appLock.get().state.collect { state ->
                if (state == LockState.LockedStrict) {
                    AppLogger.info(TAG, "strict app lock engaged; stopping the network service")
                    // The coordinator explicitly, not just the service. onDestroy() only clears
                    // `isRunning` and cancels this scope — it has never stopped the network — so
                    // the relay listener, the outbox drain and the retry loops would carry on in
                    // the process with the database reopening under them, and "nothing is
                    // delivered while locked" would be a caption over a running stack.
                    runCatching { networkCoordinator.get().stop() }
                        .onFailure { AppLogger.warn(TAG, "stopping the network for the lock failed: ${it.message}") }
                    // The notification first, even though this service is about to die. Android
                    // gives a service started with startForegroundService() five seconds to call
                    // startForeground(), and killing it inside that window is
                    // ForegroundServiceDidNotStartInTimeException — which is how this fix first
                    // announced itself. enterForeground() is idempotent, so the ordinary path
                    // having already run costs nothing.
                    enterForeground()
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Satisfies the foreground-service contract, once.
     *
     * Both the ordinary start and the lock-engaged stop go through here, because whichever of
     * them happens first has to be the one that calls `startForeground()`.
     */
    private fun enterForeground() {
        if (foregroundStarted) return
        foregroundStarted = true
        val notification = networkNotificationManager.buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NetworkNotificationManager.NOTIFICATION_ID_NETWORK,
                notification,
                foregroundServiceType(),
            )
        } else {
            startForeground(NetworkNotificationManager.NOTIFICATION_ID_NETWORK, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        val listenPort = intent?.getIntExtra(EXTRA_LISTEN_PORT, DEFAULT_LISTEN_PORT) ?: DEFAULT_LISTEN_PORT
        val forwardPort = intent?.getIntExtra(EXTRA_FORWARD_PORT, listenPort) ?: listenPort
        val useDevBootstrap = intent?.getBooleanExtra(EXTRA_USE_DEV_BOOTSTRAP, false) ?: false
        NetworkConfig.useDevBootstrap = useDevBootstrap
        val directHost = if (useDevBootstrap) DEV_EMULATOR_HOST else null
        val directPort = if (useDevBootstrap) forwardPort else null
        // Stand down rather than crash. Under the strict app lock there is no passphrase to open
        // the database with, so there is nothing this service can usefully do; NOT_STICKY stops
        // the system bringing it straight back into the same wall. The unlock starts it again.
        //
        // The flag alone is not enough and used to be the whole guard. `isLocked` is set when a
        // process learns the key is behind the lock, and this service runs in processes that may
        // not have learned it yet — the system's sticky restart and the keep-alive worker both
        // get here with a warm-up still in flight. So the start itself is the test: building the
        // coordinator provisions a DAO, which is precisely the thing that throws, and catching it
        // covers both the flag and the race.
        val refused = databaseKeyProvider.isLocked || runCatching {
            networkCoordinator.get().start(
                listenPort = listenPort,
                directHost = directHost,
                directPort = directPort,
            )
        }.onFailure { AppLogger.warn(TAG, "network start refused: ${it.message}") }.isFailure
        return if (refused) {
            AppLogger.warn(TAG, "started while the app lock holds the database shut; standing down")
            stopSelf(startId)
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * `remoteMessaging` from API 34: unlike `dataSync` it is neither capped by
     * Android 15's 6 h budget nor excluded from the Android 14 list of types a
     * BOOT_COMPLETED receiver may start.
     */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    companion object {
        const val EXTRA_LISTEN_PORT = "listen_port"
        const val EXTRA_FORWARD_PORT = "forward_port"
        const val EXTRA_USE_DEV_BOOTSTRAP = "use_dev_bootstrap"
        const val DEFAULT_LISTEN_PORT = 48555
        private const val DEV_EMULATOR_HOST = "10.0.2.2"
        private const val TAG = "Network"

        /**
         * Whether an instance is alive right now. Read from other processes'
         * components in this app only ([NetworkKeepAliveWorker]); volatile
         * because the worker runs on a WorkManager thread.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Intent that starts the service with the default listen/forward ports. */
        fun startIntent(context: Context): Intent =
            Intent(context, NetworkLifecycleService::class.java).apply {
                putExtra(EXTRA_LISTEN_PORT, DEFAULT_LISTEN_PORT)
                putExtra(EXTRA_FORWARD_PORT, DEFAULT_LISTEN_PORT)
            }
    }
}
