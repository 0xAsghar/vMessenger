package ir.vmessenger.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * First-run permissions, asked where they are needed and then out of the way: each returns a
 * function that asks (unless already granted) and runs `then` whatever the answer, so a refusal
 * never strands the person on the onboarding screen.
 */

/**
 * The battery-optimisation exemption, so the network service keeps running in the background and
 * messages and calls arrive. Asked when the node is chosen, the moment the app starts relying on it.
 */
@Composable
internal fun rememberBackgroundActivityThen(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending?.invoke()
        pending = null
    }
    return remember(context, launcher) {
        val ask: (() -> Unit) -> Unit = { then ->
            if (context.ignoresBatteryOptimizations()) {
                then()
            } else {
                pending = then
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                // Some OEM builds have no such screen; carry on rather than stop here.
                if (runCatching { launcher.launch(intent) }.isFailure) {
                    pending = null
                    then()
                }
            }
        }
        ask
    }
}

/** Location, asked right after the ID is created so sharing and the map work from the start. */
@Composable
internal fun rememberLocationPermissionThen(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.invoke()
        pending = null
    }
    return remember(context, launcher) {
        val ask: (() -> Unit) -> Unit = { then ->
            if (context.hasLocationPermission()) {
                then()
            } else {
                pending = then
                launcher.launch(LOCATION_PERMISSIONS)
            }
        }
        ask
    }
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun Context.ignoresBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

private fun Context.hasLocationPermission(): Boolean = LOCATION_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}
