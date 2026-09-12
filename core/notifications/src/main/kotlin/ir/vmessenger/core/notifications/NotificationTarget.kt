package ir.vmessenger.core.notifications

/**
 * The activity a notification tap must open.
 *
 * `core:notifications` deliberately does not depend on `:app`, so the app module
 * implements this and binds it into the Hilt graph; the notification layer only
 * needs *a* class to point an [android.content.Intent] at.
 */
interface NotificationTarget {
    val activityClass: Class<*>
}
