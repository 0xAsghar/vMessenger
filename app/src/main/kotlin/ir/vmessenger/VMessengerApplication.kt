package ir.vmessenger

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VMessengerApplication : Application() {
    override fun onCreate() {
        System.loadLibrary("sqlcipher")
        super.onCreate()
    }
}
