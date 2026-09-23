package ir.vmessenger.core.designsystem.foundation

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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** The three answers Android gives about a runtime permission, plus the one it cannot: never asked. */
enum class PermissionStatus { Granted, NotAsked, Denied, PermanentlyDenied }

/**
 * A runtime permission and the only two things a screen can do about it: ask, or — once Android
 * has stopped showing its dialog, when asking again does nothing at all — send the user to the
 * app's settings page.
 */
@Stable
class RuntimePermission internal constructor(
    val status: PermissionStatus,
    private val onRequest: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    val granted: Boolean get() = status == PermissionStatus.Granted

    fun request() = onRequest()

    fun openSettings() = onOpenSettings()
}

/**
 * [permissions] as state, re-read on every resume: the trip to Settings that a permanent denial
 * sends the user on is only noticed when they come back. With several, any one granted is
 * enough — location asks for fine and coarse, and coarse alone still works.
 */
@Composable
fun rememberRuntimePermission(vararg permissions: String): RuntimePermission {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val wanted = remember(*permissions) { permissions.toList() }
    var asked by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf(resolve(context, activity, wanted, asked)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        status = resolve(context, activity, wanted, asked = true)
    }
    LifecycleResumeEffect(context, activity, wanted, asked) {
        status = resolve(context, activity, wanted, asked)
        onPauseOrDispose { }
    }
    return remember(status, launcher, context, wanted) {
        RuntimePermission(
            status = status,
            onRequest = { launcher.launch(wanted.toTypedArray()) },
            onOpenSettings = { context.openAppSettings() },
        )
    }
}

/**
 * "Permanently denied" is not a flag Android exposes: it is *denied, no rationale offered, and
 * asked at least once*. [asked] is saveable so a process death does not turn that back into a
 * first ask.
 */
private fun resolve(context: Context, activity: Activity?, permissions: List<String>, asked: Boolean) = when {
    permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ->
        PermissionStatus.Granted
    activity != null && permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } ->
        PermissionStatus.Denied
    asked -> PermissionStatus.PermanentlyDenied
    else -> PermissionStatus.NotAsked
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
