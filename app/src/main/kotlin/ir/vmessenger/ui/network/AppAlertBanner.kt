package ir.vmessenger.ui.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * What the app can raise at the top of the main tabs.
 *
 * All four share one shape: the app looks perfectly healthy while messages
 * cannot reach it, and nothing else in the UI would ever say so. They are ranked
 * rather than stacked — two banners across the top would only hide each other —
 * and the passing conditions come first, because the permission one is the only
 * one that does not clear on its own and would otherwise bury them.
 *
 * Every alert can be closed, including the permission: an alert that could never
 * be closed would take a band of the screen for as long as the user leaves the
 * permission off, which is indefinitely. The dismissal is remembered in saved
 * state rather than on disk, so a denied permission is put back in front of them
 * on the next launch.
 */
internal enum class AppAlert(
    @StringRes val title: Int,
    @StringRes val body: Int,
) {
    IDENTITY_ELSEWHERE(R.string.alert_identity_title, R.string.alert_identity_body),
    CLOCK_SKEW(R.string.alert_clock_title, R.string.alert_clock_skew_body),
    CLOCK_CERTIFICATE(R.string.alert_clock_title, R.string.alert_clock_certificate_body),
    NOTIFICATIONS_OFF(R.string.alert_notifications_title, R.string.alert_notifications_body),
}

/**
 * What the root knows about the banner and must keep across a lock, which tears the navigation
 * graph down: whether the notification alert may be raised yet, and which alert the user closed.
 */
@Stable
internal class AppAlertHost(
    val notificationAlertAllowed: Boolean,
    private val dismissedState: MutableState<String?>,
) {
    val dismissed: String? get() = dismissedState.value

    fun dismiss(alert: AppAlert) {
        dismissedState.value = alert.name
    }

    /** The condition cleared: a fresh occurrence of it is shown again rather than silently kept down. */
    fun forget() {
        dismissedState.value = null
    }
}

/** Provided by the root around the navigation graph; null where there is no root, as in a preview. */
internal val LocalAppAlertHost = staticCompositionLocalOf<AppAlertHost?> { null }

@Composable
internal fun rememberAppAlertHost(notificationAlertAllowed: Boolean): AppAlertHost {
    val dismissed = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(notificationAlertAllowed, dismissed) { AppAlertHost(notificationAlertAllowed, dismissed) }
}

/**
 * The alert to show now, or null. "Notifications are off" only once the user has been asked and
 * answered — before that it is simply true of every fresh Android 13+ install — which is what
 * [AppAlertHost.notificationAlertAllowed] carries.
 */
@Composable
internal fun visibleAppAlert(host: AppAlertHost): AppAlert? {
    val listenerAlert by NetworkPathTracker.listenerAlert.collectAsStateWithLifecycle()
    val alert = currentAlert(notificationsEnabled() || !host.notificationAlertAllowed, listenerAlert)
    LaunchedEffect(alert) {
        if (alert == null) host.forget()
    }
    return alert?.takeIf { it.name != host.dismissed }
}

/**
 * The alert banner: a soft critical card at the top of the main tabs, in line with the content
 * rather than over it — as an overlay it sat on the app bar and ate its taps, the chats screen's
 * title and search button among them.
 */
@Composable
internal fun AppAlertBanner(
    alert: AppAlert?,
    onDismiss: (AppAlert) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept after the alert clears so the banner still has something to draw while it folds away.
    var shown by remember { mutableStateOf(alert) }
    if (alert != null) shown = alert
    AnimatedVisibility(
        visible = alert != null,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        shown?.let { current -> AlertSurface(alert = current, onDismiss = { onDismiss(current) }) }
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
    VmSurface(
        color = VmTheme.colors.bgCriticalSubtle,
        contentColor = VmTheme.colors.textCritical,
        shape = VmShapes.card,
        modifier = Modifier
            .fillMaxWidth()
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
            VmIcon(imageVector = Icons.Rounded.Warning, contentDescription = null, tint = VmTheme.colors.iconCritical)
            Spacer(Modifier.width(VmSpacing.md))
            Column(Modifier.weight(1f)) {
                VmText(
                    text = stringResource(alert.title),
                    style = VmTheme.typography.bodyMdMedium,
                    color = VmTheme.colors.textPrimary,
                )
                VmText(
                    text = stringResource(alert.body),
                    style = VmTheme.typography.bodySm,
                    color = VmTheme.colors.textSecondary,
                )
                if (alert == AppAlert.NOTIFICATIONS_OFF) {
                    VmTextButton(
                        text = stringResource(R.string.alert_notifications_action),
                        onClick = { openNotificationSettings(context) },
                    )
                }
            }
            VmIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.alert_dismiss),
                onClick = onDismiss,
                tint = VmTheme.colors.iconSecondary,
            )
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
