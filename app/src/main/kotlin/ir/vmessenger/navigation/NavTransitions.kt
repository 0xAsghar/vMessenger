package ir.vmessenger.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

/**
 * Material "shared axis X" for the outer graph: the incoming screen slides a
 * quarter of the container while both cross-fade. [SlideDirection.Start] and
 * [SlideDirection.End] are resolved against the layout direction, so the motion
 * reads correctly in the app's RTL layout without a second set of transitions.
 */
private const val SLIDE_DURATION_MS = 280
private const val FADE_DURATION_MS = 180
private const val SLIDE_FRACTION = 4

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisEnter(): EnterTransition =
    slideIntoContainer(
        towards = SlideDirection.Start,
        animationSpec = tween(SLIDE_DURATION_MS),
        initialOffset = { it / SLIDE_FRACTION },
    ) + fadeIn(tween(FADE_DURATION_MS))

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisExit(): ExitTransition =
    slideOutOfContainer(
        towards = SlideDirection.Start,
        animationSpec = tween(SLIDE_DURATION_MS),
        targetOffset = { it / SLIDE_FRACTION },
    ) + fadeOut(tween(FADE_DURATION_MS))

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisPopEnter(): EnterTransition =
    slideIntoContainer(
        towards = SlideDirection.End,
        animationSpec = tween(SLIDE_DURATION_MS),
        initialOffset = { it / SLIDE_FRACTION },
    ) + fadeIn(tween(FADE_DURATION_MS))

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.sharedAxisPopExit(): ExitTransition =
    slideOutOfContainer(
        towards = SlideDirection.End,
        animationSpec = tween(SLIDE_DURATION_MS),
        targetOffset = { it / SLIDE_FRACTION },
    ) + fadeOut(tween(FADE_DURATION_MS))
