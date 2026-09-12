package ir.vmessenger.app.network

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.notifications.NetworkNotificationManager
import ir.vmessenger.data.network.NetworkCoordinator
import javax.inject.Inject

@AndroidEntryPoint
class NetworkLifecycleService : Service() {
    @Inject
    lateinit var networkCoordinator: NetworkCoordinator

    @Inject
    lateinit var networkNotificationManager: NetworkNotificationManager

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val listenPort = intent?.getIntExtra(EXTRA_LISTEN_PORT, DEFAULT_LISTEN_PORT) ?: DEFAULT_LISTEN_PORT
        val forwardPort = intent?.getIntExtra(EXTRA_FORWARD_PORT, listenPort) ?: listenPort
        val useDevBootstrap = intent?.getBooleanExtra(EXTRA_USE_DEV_BOOTSTRAP, false) ?: false
        NetworkConfig.useDevBootstrap = useDevBootstrap
        val directHost = if (useDevBootstrap) DEV_EMULATOR_HOST else null
        val directPort = if (useDevBootstrap) forwardPort else null
        networkCoordinator.start(
            listenPort = listenPort,
            directHost = directHost,
            directPort = directPort,
        )
        return START_STICKY
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
