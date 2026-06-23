package ir.vmessenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.core.designsystem.theme.RtlLayout
import ir.vmessenger.core.designsystem.theme.VMessengerTheme
import ir.vmessenger.navigation.VMessengerNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val darkThemePref by viewModel.darkTheme.collectAsStateWithLifecycle()
            val darkTheme = when (val pref = darkThemePref) {
                null -> isSystemInDarkTheme()
                else -> pref
            }

            RtlLayout {
                VMessengerTheme(darkTheme = darkTheme) {
                    VMessengerNavHost()
                }
            }
        }
    }
}
