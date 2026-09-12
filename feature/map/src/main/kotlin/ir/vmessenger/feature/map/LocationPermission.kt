package ir.vmessenger.feature.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * The location permission as state, re-read on every resume.
 *
 * Reading it once into a `remember` (what the old screen did) goes stale the moment the user
 * grants or revokes it in Settings, which is precisely the trip the "permanently denied" branch
 * sends them on.
 */
@Composable
internal fun rememberLocationPermission(onChanged: (MapPermission) -> Unit): LocationPermissionController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var asked by rememberSaveable { mutableStateOf(false) }
    var state by remember { mutableStateOf(resolvePermission(context, activity, asked = false)) }
    val callback = rememberUpdatedState(onChanged)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        state = resolvePermission(context, activity, asked = true)
    }
    LifecycleResumeEffect(context, activity, asked) {
        state = resolvePermission(context, activity, asked)
        onPauseOrDispose { }
    }
    LaunchedEffect(state) { callback.value(state) }

    return remember(state, launcher, context) {
        LocationPermissionController(
            state = state,
            request = { launcher.launch(LOCATION_PERMISSIONS) },
            openSettings = { context.openAppSettings() },
        )
    }
}

/**
 * "Permanently denied" is not a flag Android exposes: it is *denied, no rationale offered, and we
 * have asked at least once*. [asked] is saveable so a process death does not turn that back into
 * a first ask.
 */
private fun resolvePermission(context: Context, activity: Activity?, asked: Boolean): MapPermission = when {
    isLocationGranted(context) -> MapPermission.Granted
    activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION) ->
        MapPermission.Denied
    asked -> MapPermission.PermanentlyDenied
    else -> MapPermission.Denied
}

private fun isLocationGranted(context: Context): Boolean = LOCATION_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
