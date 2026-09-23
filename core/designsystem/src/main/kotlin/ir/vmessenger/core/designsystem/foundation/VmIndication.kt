package ir.vmessenger.core.designsystem.foundation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import kotlinx.coroutines.launch

/**
 * What a press looks like: the content colour laid over the pressed thing at a low alpha, faded in
 * and out. No ripple.
 *
 * A state layer rather than Material's ripple, because the ripple is the single most recognisable
 * piece of Material's visual identity and this app no longer has one. The overlay takes the colour
 * of the content it covers, so it darkens a light row and lightens a filled button, and it is drawn
 * inside whatever clip the component applies — pressed pills stay pills.
 */
object VmIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        StateLayerNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = javaClass.hashCode()
}

private class StateLayerNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val active = mutableListOf<Interaction>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press, is HoverInteraction.Enter, is FocusInteraction.Focus ->
                        active += interaction
                    is PressInteraction.Release -> active.remove(interaction.press)
                    is PressInteraction.Cancel -> active.remove(interaction.press)
                    is HoverInteraction.Exit -> active.remove(interaction.enter)
                    is FocusInteraction.Unfocus -> active.remove(interaction.focus)
                }
                val target = when {
                    active.any { it is PressInteraction.Press } -> PRESSED_ALPHA
                    active.isNotEmpty() -> HOVERED_ALPHA
                    else -> 0f
                }
                launch { alpha.animateTo(target, tween(FADE_MS)) }
            }
        }
    }

    /**
     * Reading [alpha] here is enough to redraw as it animates: a draw node observes the state it
     * reads. The colour is the content colour where the clickable sits — which is why a component
     * with a fill emits its clickable inside its own content-colour provider.
     */
    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0f) drawRect(color = currentValueOf(LocalVmContentColor).copy(alpha = a))
    }

    private companion object {
        const val PRESSED_ALPHA = 0.10f
        const val HOVERED_ALPHA = 0.06f
        const val FADE_MS = 120
    }
}
