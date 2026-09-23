package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The spinner: a thin arc chasing itself, in the text colour by default. For "something is happening
 * and I cannot say how much of it is done". Announced as indeterminate progress to a screen reader.
 */
@Composable
fun VmProgressIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    color: Color = VmTheme.colors.iconPrimary,
    strokeWidth: Dp = VmSizes.progressStroke,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(ROTATION_MS, easing = LinearEasing)),
        label = "rotation",
    )
    val sweep by transition.animateFloat(
        initialValue = MIN_SWEEP,
        targetValue = MAX_SWEEP,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS), repeatMode = RepeatMode.Reverse),
        label = "sweep",
    )
    Canvas(
        modifier
            .size(size)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** A ring that fills as [progress] (0..1) goes up: for a transfer whose size is known. */
@Composable
@Suppress("LongParameterList") // Geometry and colours of one ring, each defaulted.
fun VmProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    color: Color = VmTheme.colors.iconAccent,
    trackColor: Color = VmTheme.colors.borderSubtle,
    strokeWidth: Dp = VmSizes.progressStroke,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Canvas(
        modifier
            .size(size)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f) },
    ) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(trackColor, 0f, FULL_TURN, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(
            color = color,
            startAngle = TOP,
            sweepAngle = FULL_TURN * clamped,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * A bar across the width: determinate when [progress] is given, a travelling segment when it is
 * null. It fills from the reading start, so it grows right-to-left in Persian.
 */
@Composable
fun VmLinearProgress(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    color: Color = VmTheme.colors.iconAccent,
    trackColor: Color = VmTheme.colors.borderSubtle,
    height: Dp = 4.dp,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val travel = if (progress == null) {
        val transition = rememberInfiniteTransition(label = "linear")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LINEAR_MS, easing = LinearEasing)),
            label = "travel",
        ).value
    } else {
        0f
    }
    val info = if (progress == null) {
        ProgressBarRangeInfo.Indeterminate
    } else {
        ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { progressBarRangeInfo = info },
    ) {
        val h = this.size.height
        val w = this.size.width
        drawLine(trackColor, Offset(0f, h / 2), Offset(w, h / 2), h, StrokeCap.Round)
        val (from, to) = if (progress != null) {
            0f to w * progress.coerceIn(0f, 1f)
        } else {
            val start = (travel * (1 + SEGMENT) - SEGMENT) * w
            start.coerceAtLeast(0f) to (start + SEGMENT * w).coerceAtMost(w)
        }
        if (to > from) {
            val a = if (rtl) w - from else from
            val b = if (rtl) w - to else to
            drawLine(color, Offset(a, h / 2), Offset(b, h / 2), h, StrokeCap.Round)
        }
    }
}

private const val FULL_TURN = 360f
private const val TOP = -90f
private const val MIN_SWEEP = 30f
private const val MAX_SWEEP = 270f
private const val ROTATION_MS = 1100
private const val SWEEP_MS = 800
private const val LINEAR_MS = 1400
private const val SEGMENT = 0.35f
