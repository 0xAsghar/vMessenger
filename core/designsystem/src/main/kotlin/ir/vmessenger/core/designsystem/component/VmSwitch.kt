package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.foundation.VmIndication
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The one toggle. The accent track when on, a neutral one when off, a white thumb either way.
 *
 * When [onCheckedChange] is null the switch only *shows* a state — the row around it owns the tap,
 * so there is exactly one touch target for one setting rather than two that race. Offsets are
 * direction-relative, so "on" sits at the reading end in both languages.
 */
@Composable
fun VmSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = VmTheme.colors
    val track by animateColorAsState(
        targetValue = when {
            !enabled -> c.bgSubtleStrong
            checked -> c.bgAccent
            else -> c.borderInteractive
        },
        animationSpec = VmMotion.emphasis(),
        label = "track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) TRACK_WIDTH - THUMB - INSET * 2 else 0.dp,
        animationSpec = VmMotion.emphasis(),
        label = "thumb",
    )
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .then(toggle)
            .size(width = TRACK_WIDTH, height = TRACK_HEIGHT)
            .background(track, CircleShape)
            .padding(INSET),
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(THUMB)
                .shadow(1.dp, CircleShape)
                .background(thumbColor(enabled), CircleShape),
        )
    }
}

/** White on light, off-white on dark — a thumb must read against both the on and the off track. */
@Composable
private fun thumbColor(enabled: Boolean): Color {
    val c = VmTheme.colors
    return when {
        !enabled -> c.bgCanvas
        c.isDark -> c.textPrimary
        else -> Color.White
    }
}

/** A tick box, for picking any number from a list. */
@Composable
fun VmCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = VmTheme.colors
    val fill by animateColorAsState(
        targetValue = if (checked) (if (enabled) c.bgAccent else c.iconDisabled) else c.bgCanvas,
        animationSpec = VmMotion.emphasis(),
        label = "fill",
    )
    val edge = when {
        checked -> fill
        enabled -> c.borderInteractive
        else -> c.borderSubtle
    }
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = VmIndication,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(toggle)
            .size(BOX)
            .background(fill, BOX_SHAPE)
            .border(BorderStroke(BOX_EDGE, edge), BOX_SHAPE),
    ) {
        if (checked) {
            VmIcon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = c.textOnSolid, size = TICK)
        }
    }
}

/** One choice among several, of which exactly one is picked. */
@Composable
fun VmRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = VmTheme.colors
    val edge by animateColorAsState(
        targetValue = when {
            !enabled -> c.borderSubtle
            selected -> c.bgAccent
            else -> c.borderInteractive
        },
        animationSpec = VmMotion.emphasis(),
        label = "edge",
    )
    val select = if (onClick != null) {
        Modifier.selectable(
            selected = selected,
            interactionSource = remember { MutableInteractionSource() },
            indication = VmIndication,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(select)
            .size(BOX)
            .border(BorderStroke(RADIO_EDGE, edge), CircleShape),
    ) {
        if (selected) Box(Modifier.size(RADIO_DOT).background(edge, CircleShape))
    }
}

private val TRACK_WIDTH = 46.dp
private val TRACK_HEIGHT = 28.dp
private val THUMB = 22.dp
private val INSET = 3.dp
private val BOX = 20.dp
private val BOX_SHAPE = RoundedCornerShape(5.dp)
private val BOX_EDGE = 1.5.dp
private val TICK = 16.dp
private val RADIO_EDGE = 2.dp
private val RADIO_DOT = 10.dp
