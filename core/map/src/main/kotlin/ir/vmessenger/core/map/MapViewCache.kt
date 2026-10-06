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
 *
 * A map that is not persistent does not come from the cache at all ([createOwned]): its view is
 * made with the composable and destroyed with it, and is never moved between parents. A live GL
 * view moved to a parent of another size is not safe — it kept its old size about one time in five.
 */
internal object MapViewCache {

    private val entries = WeakHashMap<LifecycleOwner, MapViewEntry>()

    fun obtain(context: Context, owner: LifecycleOwner, savedState: Bundle): MapView =
        entries[owner]?.view ?: create(context, owner, savedState).view

    private fun create(context: Context, owner: LifecycleOwner, savedState: Bundle): MapViewEntry {
        val entry = newEntry(context, owner, savedState, shared = true)
        entries[owner] = entry
        entry.bind()
        return entry
    }

    /**
     * A view that belongs to the caller, not to the owner: [MapViewEntry.release] takes it down, and until
     * then it follows the owner's lifecycle like the cached one does. For a map embedded in a screen.
     */
    fun createOwned(context: Context, owner: LifecycleOwner, savedState: Bundle): MapViewEntry =
        newEntry(context, owner, savedState, shared = false).also { it.bind() }

    private fun newEntry(context: Context, owner: LifecycleOwner, savedState: Bundle, shared: Boolean): MapViewEntry {
        val view = MapView(context)
        view.onCreate(savedState.takeIf { !it.isEmpty })
        return MapViewEntry(view, context.applicationContext, owner, shared)
    }

    fun forget(owner: LifecycleOwner) {
        entries.remove(owner)
    }
}

/**
 * Forwards one owner's lifecycle and the system's memory callbacks to one map view.
 *
 * A [shared] entry lives as long as its owner. An owned one can be [release]d earlier, from the
 * middle of its owner's life, so it remembers how far up the lifecycle the view has been taken.
 */
internal class MapViewEntry(
    val view: MapView,
    private val appContext: Context,
    private val owner: LifecycleOwner,
    private val shared: Boolean,
) : LifecycleEventObserver, ComponentCallbacks2 {

    private var started = false
    private var resumed = false
    private var released = false

    fun bind() {
        owner.lifecycle.addObserver(this)
        appContext.registerComponentCallbacks(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                started = true
                view.onStart()
            }
            Lifecycle.Event.ON_RESUME -> {
                resumed = true
                view.onResume()
            }
            Lifecycle.Event.ON_PAUSE -> {
                resumed = false
                view.onPause()
            }
            Lifecycle.Event.ON_STOP -> {
                started = false
                view.onStop()
            }
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

    /** Safe to call twice: the owner's `ON_DESTROY` and the composable leaving may both get here. */
    fun release() {
        if (released) return
        released = true
        owner.lifecycle.removeObserver(this)
        appContext.unregisterComponentCallbacks(this)
        // Released early, the view is still started: take it down the way its owner would have.
        if (resumed) view.onPause()
        if (started) view.onStop()
        view.detachFromParent()
        view.onDestroy()
        if (shared) MapViewCache.forget(owner)
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
