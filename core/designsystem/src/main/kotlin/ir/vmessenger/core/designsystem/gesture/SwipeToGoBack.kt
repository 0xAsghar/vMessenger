package ir.vmessenger.core.designsystem.gesture

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** How far the finger has to travel before the gesture counts as "go back". */
private val SwipeBackTravel = 72.dp

/**
 * Drag left-to-right across a screen to go back.
 *
 * **Left to right is the back direction here because the app is right-to-left.** Content advances
 * end-ward — in Persian that is leftward — so returning is the reverse, and the same drag that
 * means "back" in an English app means "forward" here. It also happens to be why this does not
 * collide with the reply gesture on a message bubble: that one is `StartToEnd`, which is direction
 * aware and therefore right-to-left in this layout. Opposite directions, no contest.
 *
 * It dispatches a real back press rather than calling a navigation callback, so every `BackHandler`
 * already registered applies in the order it would for the system gesture: on the conversation
 * screen that means an open reply is cleared first, and only a second swipe leaves the chat.
 * Anything else that installs a handler later gets the same treatment for free.
 *
 * Fires on release rather than the moment the threshold is crossed, so a drag can be taken back by
 * returning the finger — a gesture that leaves the screen from under you while you are still
 * touching it is the one people complain about.
 *
 * Attach it to the *content*, not the whole screen: a horizontal drag that starts in the composer
 * belongs to the composer, where the mic's slide-to-cancel lives.
 */
@Composable
fun Modifier.swipeToGoBack(enabled: Boolean = true): Modifier {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val travelPx = with(LocalDensity.current) { SwipeBackTravel.toPx() }
    if (!enabled || dispatcher == null) return this
    return pointerInput(dispatcher, travelPx) {
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragCancel = { travelled = 0f },
            onDragEnd = { if (travelled >= travelPx) dispatcher.onBackPressed() },
        ) { _, dragAmount ->
            // Pointer deltas are physical, not layout-relative: positive is rightward on any
            // device, which is what "left to right" means and what this gesture is.
            travelled += dragAmount
        }
    }
}
