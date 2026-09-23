package ir.vmessenger.core.designsystem.foundation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmTheme
import java.util.UUID

/**
 * A window of its own covering the whole screen, system bars included, with [content] composed in
 * it as part of the caller's composition — the caller's theme, locals and state all carry over.
 *
 * What a modal sheet needs and a Compose `Dialog` does not give: that sizes its window from the
 * configuration's screen size, which on older Android leaves out the navigation bar, so a sheet
 * stopped short of the bottom edge and its scrim left the bars undimmed. This window is full-size,
 * transparent and edge to edge, and draws nothing itself; the content is the scrim and the sheet.
 *
 * Back is handed to [onBackPress] rather than closing the window, so the sheet can animate out
 * first. The window inherits `FLAG_SECURE` from the screen that opened it: a sheet over a secured
 * conversation must not be the one thing a screenshot can see.
 */
@Composable
internal fun VmModalWindow(
    onBackPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val layoutDirection = LocalLayoutDirection.current
    val dark = VmTheme.colors.isDark
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    // Saved so the window's own saved state finds itself again after a configuration change.
    val id = rememberSaveable { UUID.randomUUID() }
    val dialog = remember(view) {
        ModalWindowDialog(view, id).apply { setContent(parent) { currentContent() } }
    }
    DisposableEffect(dialog) {
        dialog.show()
        onDispose {
            dialog.dismiss()
            dialog.disposeComposition()
        }
    }
    SideEffect {
        dialog.update(onBackPress, layoutDirection, dark)
    }
}

private class ModalWindowDialog(
    private val composeView: View,
    id: UUID,
) : ComponentDialog(composeView.context, R.style.VmModalWindow) {

    private var onBackPress: () -> Unit = {}
    private val layout = ModalWindowLayout(context)

    init {
        val window = checkNotNull(window) { "A dialog always has a window" }
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setWindowAnimations(0)
        // Below Android 11 the keyboard's inset only reaches a window that resizes for it.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (composeView.isFlagSecure()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // The key the composition's saved state is filed under; without it this window would share
        // the activity's key and the second registration would throw.
        layout.setTag(androidx.compose.ui.R.id.compose_view_saveable_id_tag, "VmModalWindow:$id")
        setContentView(layout)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // The content belongs to the screen that opened it: its lifecycle, its view models, its
        // saved state — not the dialog's own, which setContentView just installed on the decor.
        layout.setViewTreeLifecycleOwner(composeView.findViewTreeLifecycleOwner())
        layout.setViewTreeViewModelStoreOwner(composeView.findViewTreeViewModelStoreOwner())
        layout.setViewTreeSavedStateRegistryOwner(composeView.findViewTreeSavedStateRegistryOwner())
        onBackPressedDispatcher.addCallback(this) { onBackPress() }
    }

    fun setContent(parent: CompositionContext, content: @Composable () -> Unit) {
        layout.setContent(parent, content)
    }

    fun update(onBackPress: () -> Unit, layoutDirection: LayoutDirection, dark: Boolean) {
        this.onBackPress = onBackPress
        layout.layoutDirection = when (layoutDirection) {
            LayoutDirection.Ltr -> View.LAYOUT_DIRECTION_LTR
            LayoutDirection.Rtl -> View.LAYOUT_DIRECTION_RTL
        }
        val window = window ?: return
        // The status bar keeps the screen's icon colour under the scrim; the navigation bar sits
        // on the sheet, which is the canvas colour.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    fun disposeComposition() {
        layout.disposeComposition()
    }

    // Only the content decides when it is done; a stray cancel would skip the exit animation.
    override fun cancel() = Unit
}

private class ModalWindowLayout(context: Context) : AbstractComposeView(context) {

    private var content: @Composable () -> Unit by mutableStateOf({})

    override var shouldCreateCompositionOnAttachedToWindow: Boolean = false
        private set

    fun setContent(parent: CompositionContext, content: @Composable () -> Unit) {
        setParentCompositionContext(parent)
        this.content = content
        shouldCreateCompositionOnAttachedToWindow = true
        createComposition()
    }

    @Composable
    override fun Content() {
        content()
    }
}

private fun View.isFlagSecure(): Boolean {
    val flags = (rootView.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
    return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
