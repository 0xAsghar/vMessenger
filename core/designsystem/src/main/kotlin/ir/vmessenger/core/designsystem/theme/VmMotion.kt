package ir.vmessenger.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * The app's motion durations, in one place.
 *
 * Before this, every `tween` in the codebase lived in `:app` — the navigation shared axis and the
 * tab cross-fade — and the feature modules had no animation at all. Collecting the numbers that
 * already existed here means per-screen work reuses them instead of inventing a fourth and fifth
 * duration, which is how motion stops feeling like one app.
 */
object VmMotion {
    /** Screen-to-screen slide. */
    const val SLIDE_MS = 280

    /** Cross-fades, and the fade half of a shared axis. */
    const val FADE_MS = 180

    /** Switching between sibling tabs, where anything longer reads as lag. */
    const val TAB_FADE_MS = 160

    /** A control appearing or disappearing in place: a FAB, a banner, a tick. */
    const val EMPHASIS_MS = 150

    /**
     * How long a re-ordered list item takes to slide to its new home.
     *
     * Short on purpose. The message list is `reverseLayout`, so a generous placement spec makes
     * the whole column appear to shift when one message arrives — exactly the effect reverseLayout
     * was chosen to avoid. The motion belongs in the *appearance* spec, not this one.
     */
    const val PLACEMENT_MS = 90

    fun <T> emphasis(): FiniteAnimationSpec<T> = tween(EMPHASIS_MS)

    fun <T> fade(): FiniteAnimationSpec<T> = tween(FADE_MS)
}
