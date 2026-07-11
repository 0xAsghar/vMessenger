package ir.vmessenger

import android.app.Application
import android.content.Intent
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import ir.vmessenger.app.network.NetworkLifecycleService
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.DatabaseKeyProvider
import org.maplibre.android.MapLibre
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class VMessengerApplication : Application() {
    @Inject lateinit var databaseKeyProvider: DatabaseKeyProvider

    private lateinit var fileLogSink: FileLogSink

    override fun onCreate() {
        System.loadLibrary("sqlcipher")
        super.onCreate()
        MapLibre.getInstance(this)
        fileLogSink = FileLogSink(this)
        AppLogger.addSink(fileLogSink)
        AppLogger.info("App", "vMessenger started")
        runBlocking { databaseKeyProvider.initialize() }
        val networkIntent = Intent(this, NetworkLifecycleService::class.java).apply {
            putExtra(NetworkLifecycleService.EXTRA_LISTEN_PORT, NetworkLifecycleService.DEFAULT_LISTEN_PORT)
            putExtra(NetworkLifecycleService.EXTRA_FORWARD_PORT, NetworkLifecycleService.DEFAULT_LISTEN_PORT)
        }
        // On Android 12+ a background process restart (e.g. START_STICKY) reaches
        // here with no foreground activity, and startForegroundService throws
        // ForegroundServiceStartNotAllowedException — which would crash-loop the
        // app. The system restarts the sticky service itself in that case.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(networkIntent)
            } else {
                startService(networkIntent)
            }
        }.onFailure {
            AppLogger.warn("App", "network service start deferred: ${it.message}")
        }
    }
}
