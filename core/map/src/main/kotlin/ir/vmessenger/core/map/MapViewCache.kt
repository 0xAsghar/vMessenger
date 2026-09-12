package ir.vmessenger.core.map

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.maps.MapView
import java.util.WeakHashMap

/**
 * One [MapView] per lifecycle owner, kept alive for that owner's whole life.
 *
 * The map tab is a navigation destination: leaving it disposes the composable. Creating the GL
 * surface again on every visit is the single most expensive thing this screen can do, so the
 * view is cached against the *activity* and merely detached from its parent in between.
 *
 * The cache also owns the whole Android contract the view needs — start/resume/pause/stop,
 * `onDestroy` and `onLowMemory` — exactly once, so returning to the tab can never dispatch a
 * second `onStart` to an already started map.
 */
internal object MapViewCache {

    private val entries = WeakHashMap<LifecycleOwner, Entry>()

    fun obtain(context: Context, owner: LifecycleOwner, savedState: Bundle): MapView =
        entries[owner]?.view ?: create(context, owner, savedState).view

    private fun create(context: Context, owner: LifecycleOwner, savedState: Bundle): Entry {
        val view = MapView(context)
        view.onCreate(savedState.takeIf { !it.isEmpty })
        val entry = Entry(view, context.applicationContext, owner)
        entries[owner] = entry
        entry.bind()
        return entry
    }

    fun forget(owner: LifecycleOwner) {
        entries.remove(owner)
    }
}

/** Forwards one owner's lifecycle and the system's memory callbacks to one map view. */
private class Entry(
    val view: MapView,
    private val appContext: Context,
    private val owner: LifecycleOwner,
) : LifecycleEventObserver, ComponentCallbacks2 {

    fun bind() {
        owner.lifecycle.addObserver(this)
        appContext.registerComponentCallbacks(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> view.onStart()
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            Lifecycle.Event.ON_STOP -> view.onStop()
            Lifecycle.Event.ON_DESTROY -> release()
            else -> Unit
        }
    }

    override fun onLowMemory() {
        view.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) view.onLowMemory()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun release() {
        owner.lifecycle.removeObserver(this)
        appContext.unregisterComponentCallbacks(this)
        view.detachFromParent()
        view.onDestroy()
        MapViewCache.forget(owner)
    }
}

/**
 * A cached view outlives the `AndroidView` that hosted it, so it must leave its old parent
 * before it can be added to a new one.
 */
internal fun MapView.detachFromParent() {
    (parent as? ViewGroup)?.removeView(this)
}

/** The hosting activity when there is one, so the map can outlive a single navigation entry. */
internal fun Context.findLifecycleActivity(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity && current is LifecycleOwner) return current
        current = current.baseContext
    }
    return null
}
