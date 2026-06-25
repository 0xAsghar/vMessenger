package ir.vmessenger.app.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.data.network.NetworkCoordinator
import javax.inject.Inject

@AndroidEntryPoint
class NetworkLifecycleService : Service() {
    @Inject
    lateinit var networkCoordinator: NetworkCoordinator

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    companion object {
        const val EXTRA_LISTEN_PORT = "listen_port"
        const val EXTRA_FORWARD_PORT = "forward_port"
        const val EXTRA_USE_DEV_BOOTSTRAP = "use_dev_bootstrap"
        const val DEFAULT_LISTEN_PORT = 48555
        private const val DEV_EMULATOR_HOST = "10.0.2.2"
    }
}
