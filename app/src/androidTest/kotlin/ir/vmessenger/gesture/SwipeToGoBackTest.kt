package ir.vmessenger.gesture

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import ir.vmessenger.core.designsystem.gesture.swipeToGoBack
import ir.vmessenger.core.designsystem.theme.RtlLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Swipe-to-go-back, asserted through the back dispatcher rather than through navigation.
 *
 * Dispatching a real back press is the point of the gesture — it is what makes an open reply clear
 * before the screen closes — so the test watches what any `BackHandler` would see. It runs inside
 * [RtlLayout] because the app does, and because the direction is the whole question: left-to-right
 * is *back* in a right-to-left app, and the message bubbles' reply gesture is the other way.
 */
class SwipeToGoBackTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeOwner : OnBackPressedDispatcherOwner {
        var presses = 0
        // createUnsafe: the state is set from the test thread, and the main-thread check the
        // ordinary constructor enforces is the only thing in the way.
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher { presses++ }
    }

    private fun swipes(gesture: androidx.compose.ui.test.TouchInjectionScope.() -> Unit): Int {
        val owner = FakeOwner()
        compose.setContent {
            RtlLayout {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                    Box(modifier = Modifier.fillMaxSize().testTag(TARGET).swipeToGoBack())
                }
            }
        }
        compose.onNodeWithTag(TARGET).performTouchInput(gesture)
        compose.waitForIdle()
        return owner.presses
    }

    @Test
    fun a_left_to_right_swipe_goes_back() {
        assertEquals(1, swipes { swipeRight() })
    }

    /** The direction the message bubbles use for reply. It must not also mean "leave the screen". */
    @Test
    fun a_right_to_left_swipe_does_not() {
        assertEquals(0, swipes { swipeLeft() })
    }

    /** Short of the threshold is a hesitation, not a command. */
    @Test
    fun a_short_drag_does_not() {
        assertEquals(0, swipes { swipeRight(startX = centerX, endX = centerX + 20f) })
    }

    private companion object {
        const val TARGET = "swipe-back-target"
    }
}
