package ir.vmessenger.ui.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.R
import ir.vmessenger.core.common.network.ListenerAlert
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * What the app can raise over every screen.
 *
 * All four share one shape: the app looks perfectly healthy while messages
 * cannot reach it, and nothing else in the UI would ever say so. They are ranked
 * rather than stacked — two banners across the top would only hide each other —
 * and the passing conditions come first, because the permission one is the only
 * one that does not clear on its own and would otherwise bury them.
 *
 * Every alert can be closed, including the permission. This banner is an
 * overlay at the top of the root Box, so it sits *on* whatever app bar is below
 * it and eats its taps: on the chats screen that is the title and the search
 * button. An alert that could never be closed would take those away for as long
 * as the user leaves the permission off, which is indefinitely. The dismissal is
 * remembered in saved state rather than on disk, so a denied permission is put
 * back in front of them on the next launch.
 */
private enum class AppAlert(
    @StringRes val title: Int,
    @StringRes val body: Int,
) {
    IDENTITY_ELSEWHERE(R.string.alert_identity_title, R.string.alert_identity_body),
    CLOCK_SKEW(R.string.alert_clock_title, R.string.alert_clock_skew_body),
    CLOCK_CERTIFICATE(R.string.alert_clock_title, R.string.alert_clock_certificate_body),
    NOTIFICATIONS_OFF(R.string.alert_notifications_title, R.string.alert_notifications_body),
}

/**
 * The app-wide alert banner. Dismissal is remembered per alert and only until
 * that alert clears, so a fresh occurrence — or a different one — surfaces again.
 */
@Composable
fun AppAlertBanner(modifier: Modifier = Modifier) {
    val listenerAlert by NetworkPathTracker.listenerAlert.collectAsStateWithLifecycle()
    val alert = currentAlert(notificationsEnabled(), listenerAlert)
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    // [shown] is kept after the alert clears so the banner still has something to
    // draw while it slides away; the dismissal is dropped at the same moment, so a
    // condition that comes back is shown again rather than silently suppressed.
    var shown by remember { mutableStateOf<AppAlert?>(null) }
    LaunchedEffect(alert) {
        if (alert == null) dismissed = null else shown = alert
    }

    AnimatedVisibility(
        visible = alert != null && alert.name != dismissed,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        shown?.let { current -> AlertSurface(alert = current, onDismiss = { dismissed = current.name }) }
    }
}

private fun currentAlert(notificationsEnabled: Boolean, listenerAlert: ListenerAlert): AppAlert? = when {
    listenerAlert == ListenerAlert.IDENTITY_ELSEWHERE -> AppAlert.IDENTITY_ELSEWHERE
    listenerAlert == ListenerAlert.CLOCK_SKEW -> AppAlert.CLOCK_SKEW
    listenerAlert == ListenerAlert.CLOCK_CERTIFICATE -> AppAlert.CLOCK_CERTIFICATE
    !notificationsEnabled -> AppAlert.NOTIFICATIONS_OFF
    else -> null
}

@Composable
private fun AlertSurface(alert: AppAlert, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = VmElevation.sheet,
        shadowElevation = VmElevation.sheet,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = VmSpacing.md, vertical = VmSpacing.sm),
    ) {
        Row(
            modifier = Modifier.padding(
                start = VmSpacing.md,
                top = VmSpacing.sm,
                bottom = VmSpacing.sm,
                end = VmSpacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Rounded.Warning, contentDescription = null)
            Spacer(Modifier.width(VmSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(text = stringResource(alert.title), style = MaterialTheme.typography.titleSmall)
                Text(text = stringResource(alert.body), style = MaterialTheme.typography.bodySmall)
                if (alert == AppAlert.NOTIFICATIONS_OFF) {
                    TextButton(onClick = { openNotificationSettings(context) }) {
                        Text(text = stringResource(R.string.alert_notifications_action))
                    }
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.alert_dismiss),
                )
            }
        }
    }
}

/**
 * Whether the system will actually deliver this app's notifications, re-read
 * every time the app comes forward. A refusal can only be undone in the system
 * settings, and the way back from there is `ON_RESUME`.
 */
@Composable
private fun notificationsEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Starts optimistic: the first ON_RESUME arrives immediately, and a banner
    // that flashes on every cold start would train the user to ignore it.
    var enabled by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

/** App notification settings, falling back to the app's own settings page. */
private fun openNotificationSettings(context: Context) {
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(notifications) }
        .onFailure { runCatching { context.startActivity(details) } }
}
