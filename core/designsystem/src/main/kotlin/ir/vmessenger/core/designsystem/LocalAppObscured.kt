package ir.vmessenger.core.designsystem

import androidx.compose.runtime.compositionLocalOf

/**
 * True while something is drawn over the whole app and the user cannot see the screen behind it —
 * today, the app lock.
 *
 * A composition local rather than a parameter because the screens that need it are several levels
 * down and do not otherwise care that a lock exists. It is deliberately about *visibility*, not
 * about locking: the question a screen needs answered is "is anyone actually looking at me", and
 * the honest answer decides whether it may mark a conversation read, send a read receipt, or tell
 * the notifier to stay quiet. A screen that is composed but covered must do none of those — a read
 * receipt cannot be taken back, and telling a peer their message was read by someone who was
 * looking at a PIN pad is a lie the protocol has no way to correct.
 *
 * Defaults to false so a preview, a test or any host that does not provide it behaves normally.
 */
val LocalAppObscured = compositionLocalOf { false }
