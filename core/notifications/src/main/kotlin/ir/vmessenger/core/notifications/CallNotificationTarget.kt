package ir.vmessenger.core.notifications

/**
 * The call screen a ringing notification opens, and the receiver its buttons reach.
 *
 * Same reason as [NotificationTarget]: `core:notifications` does not depend on `:app`, so the app
 * module names the classes and this layer only needs something to point an
 * [android.content.Intent] at. A separate interface because a call does not open the main
 * activity — it opens its own, over the lock screen.
 */
interface CallNotificationTarget {
    val callActivityClass: Class<*>

    val callActionReceiverClass: Class<*>
}
