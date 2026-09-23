package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import ir.vmessenger.core.designsystem.LocalAppObscured
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * A menu that opens from the thing that was pressed: a rounded card of plain rows, lifted by a
 * shadow, growing out of its anchor and fading away when closed.
 *
 * It anchors to the layout it is placed in, so put it in the same `Box` as its button. It opens
 * under that anchor, starting at its reading edge, and moves to the other edge or above it when
 * the screen has no room. A tap outside or back closes it through [onDismissRequest].
 *
 * **Nothing above the lock**: a popup is a window of its own, so while the app is obscured it is
 * not composed, like the dialogs and sheets.
 */
@Composable
fun VmDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded && !LocalAppObscured.current
    if (!visibility.currentState && !visibility.targetState) return
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val position = remember(density) {
        MenuPosition(density) { anchor, menu -> origin = transformOrigin(anchor, menu) }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(VmMotion.emphasis()) +
                scaleIn(VmMotion.emphasis(), initialScale = INITIAL_SCALE, transformOrigin = origin),
            exit = fadeOut(VmMotion.emphasis()),
        ) {
            VmSurface(
                shape = VmShapes.menu,
                color = VmTheme.colors.bgElevated,
                contentColor = VmTheme.colors.textPrimary,
                shadowElevation = VmElevation.fab,
                modifier = modifier.padding(MENU_SHADOW_ROOM),
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = VmSpacing.sm)
                        .width(IntrinsicSize.Max)
                        .widthIn(min = MIN_WIDTH, max = MAX_WIDTH)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/**
 * One row of a [VmDropdownMenu]. [selected] marks the current choice of a set with a check at the
 * row's end and says so to a screen reader; [destructive] colours the whole row as a warning.
 */
@Composable
@Suppress("LongParameterList") // A row's optional parts, each defaulted.
fun VmDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val c = VmTheme.colors
    val color = when {
        !enabled -> c.textDisabled
        destructive -> c.textCritical
        else -> c.textPrimary
    }
    VmSurface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        contentColor = color,
        role = Role.Button,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (selected) this.selected = true },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
            modifier = Modifier
                .heightIn(min = ITEM_HEIGHT)
                .padding(horizontal = VmSpacing.lg),
        ) {
            if (leadingIcon != null) {
                VmIcon(imageVector = leadingIcon, contentDescription = null, tint = color)
            }
            VmText(
                text = text,
                style = VmTheme.typography.bodyLg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                VmIcon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = c.iconAccent,
                    size = CHECK_SIZE,
                )
            } else {
                Spacer(Modifier.size(CHECK_SIZE))
            }
        }
    }
}

/**
 * Under the anchor, starting at its reading edge; flipped to the other edge, or above the anchor,
 * when that would leave the window. Reports where it put the menu so the grow animation can start
 * from the anchor rather than from the middle of the menu.
 */
private class MenuPosition(
    density: Density,
    private val onPlaced: (anchor: IntRect, menu: IntRect) -> Unit,
) : PopupPositionProvider {
    private val margin = with(density) { WINDOW_MARGIN.roundToPx() }
    private val shadowRoom = with(density) { MENU_SHADOW_ROOM.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        // The content carries [MENU_SHADOW_ROOM] of padding for its shadow; line up the card, not it.
        val startAligned = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left - shadowRoom
        } else {
            anchorBounds.right - width + shadowRoom
        }
        val endAligned = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - width + shadowRoom
        } else {
            anchorBounds.left - shadowRoom
        }
        val fitsX = { x: Int -> x >= margin && x + width <= windowSize.width - margin }
        val x = when {
            fitsX(startAligned) -> startAligned
            fitsX(endAligned) -> endAligned
            else -> endAligned.coerceIn(margin, (windowSize.width - width - margin).coerceAtLeast(margin))
        }
        val below = anchorBounds.bottom - shadowRoom
        val above = anchorBounds.top - height + shadowRoom
        val y = when {
            below + height <= windowSize.height - margin -> below
            above >= margin -> above
            else -> below.coerceIn(margin, (windowSize.height - height - margin).coerceAtLeast(margin))
        }
        onPlaced(anchorBounds, IntRect(x, y, x + width, y + height))
        return IntOffset(x, y)
    }
}

/** The point of the menu nearest its anchor, as fractions of the menu's size. */
private fun transformOrigin(anchor: IntRect, menu: IntRect): TransformOrigin {
    if (menu.width == 0 || menu.height == 0) return TransformOrigin.Center
    val pivotX = ((anchor.center.x - menu.left).toFloat() / menu.width).coerceIn(0f, 1f)
    val pivotY = if (menu.top >= anchor.top) 0f else 1f
    return TransformOrigin(pivotX, pivotY)
}

/** Grows from nine-tenths of its size: enough to read as coming from the anchor, not as a zoom. */
private const val INITIAL_SCALE = 0.9f
private val MIN_WIDTH = 160.dp
private val MAX_WIDTH = 280.dp
private val ITEM_HEIGHT = 48.dp
private val CHECK_SIZE = 20.dp
private val WINDOW_MARGIN = 8.dp

/** Room around the card for its shadow to draw into; the popup window clips at its own edge. */
private val MENU_SHADOW_ROOM = 8.dp
