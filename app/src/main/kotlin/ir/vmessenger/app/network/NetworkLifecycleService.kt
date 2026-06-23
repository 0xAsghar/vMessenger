package ir.vmessenger.app.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.usecase.discovery.JoinNetworkUseCase
import ir.vmessenger.domain.usecase.discovery.PublishEndpointUseCase
import ir.vmessenger.network.messaging.MessagingService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NetworkLifecycleService : Service() {
    @Inject
    lateinit var joinNetworkUseCase: JoinNetworkUseCase

    @Inject
    lateinit var publishEndpointUseCase: PublishEndpointUseCase

    @Inject
    lateinit var messagingService: MessagingService

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val listenPort = intent?.getIntExtra(EXTRA_LISTEN_PORT, DEFAULT_LISTEN_PORT) ?: DEFAULT_LISTEN_PORT
        val forwardPort = intent?.getIntExtra(EXTRA_FORWARD_PORT, listenPort) ?: listenPort
        scope.launch {
            joinNetworkUseCase()
            publishEndpointUseCase(host = "10.0.2.2", port = forwardPort)
            messagingService.startListening(listenPort)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LISTEN_PORT = "listen_port"
        const val EXTRA_FORWARD_PORT = "forward_port"
        const val DEFAULT_LISTEN_PORT = 48555
    }
}
