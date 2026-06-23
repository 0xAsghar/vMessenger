package ir.vmessenger

import android.app.Application
import android.content.Intent
import dagger.hilt.android.HiltAndroidApp
import ir.vmessenger.app.network.NetworkLifecycleService
import ir.vmessenger.core.database.DatabaseKeyProvider
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class VMessengerApplication : Application() {
    @Inject lateinit var databaseKeyProvider: DatabaseKeyProvider

    override fun onCreate() {
        System.loadLibrary("sqlcipher")
        super.onCreate()
        runBlocking { databaseKeyProvider.initialize() }
        startService(
            Intent(this, NetworkLifecycleService::class.java).apply {
                putExtra(NetworkLifecycleService.EXTRA_LISTEN_PORT, NetworkLifecycleService.DEFAULT_LISTEN_PORT)
                putExtra(NetworkLifecycleService.EXTRA_FORWARD_PORT, NetworkLifecycleService.DEFAULT_LISTEN_PORT)
            },
        )
    }
}
