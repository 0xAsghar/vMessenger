package ir.vmessenger.core.map

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ir.vmessenger.core.location.DeviceLocationProvider

/**
 * A MapLibre map as a composable.
 *
 * Nothing about it is driven from `AndroidView(update = …)`: markers, camera, style and puck are
 * each applied from their own keyed effect, so recomposing the caller (which happens on every
 * location sample) costs exactly nothing. The view itself is cached per activity, so switching
 * tabs detaches it rather than destroying the GL surface.
 *
 * @param content the pins to draw and what the camera should do; structural equality on it
 *  decides whether the map is touched at all.
 * @param locationProvider drives the "my position" puck through [BusLocationEngine]; null leaves
 *  the puck to MapLibre's default engine, which the app deliberately never uses.
 */
@Composable
fun VmMapView(
    content: MapContent,
    options: VmMapOptions,
    callbacks: VmMapCallbacks,
    modifier: Modifier = Modifier,
    locationProvider: DeviceLocationProvider? = null,
) {
    val binding = rememberMapBinding(locationProvider, options.persistent)
    SideEffect { binding.controller.callbacks = callbacks }
    MapEffects(binding, content, options)
    AndroidView(
        factory = {
            binding.mapView.detachFromParent()
            binding.mapView
        },
        modifier = modifier.fillMaxSize(),
        onRelease = { it.detachFromParent() },
    )
}

@Composable
private fun rememberMapBinding(locationProvider: DeviceLocationProvider?, persistent: Boolean): MapBinding {
    val context = LocalContext.current
    val entryOwner = LocalLifecycleOwner.current
    // The activity, not the navigation entry: a cached view has to outlive the map tab. A
    // non-persistent map stays on its own screen's owner, so the two never share one view.
    val owner = remember(context, entryOwner, persistent) {
        if (persistent) context.findLifecycleActivity() ?: entryOwner else entryOwner
    }
    val savedState = rememberSaveable { Bundle() }
    val bitmaps = rememberMarkerBitmaps()
    val scope = rememberCoroutineScope()
    val engine = remember(locationProvider) { locationProvider?.let(::BusLocationEngine) }
    val mapView = remember(owner) { MapViewCache.obtain(context, owner, savedState) }
    val controller = remember(mapView, bitmaps, engine, scope) {
        MapController(context.applicationContext, mapView, bitmaps, engine, scope)
    }
    return remember(controller) { MapBinding(controller, mapView, savedState, owner) }
}

@Composable
private fun MapEffects(
    binding: MapBinding,
    content: MapContent,
    options: VmMapOptions,
) {
    val controller = binding.controller
    val markers = content.markers
    val camera = content.camera
    var styleGeneration by remember(controller) { mutableIntStateOf(0) }
    val latestMarkers = rememberUpdatedState(markers)
    // A list of ids, so "fit all" re-fits when the SET of contacts changes — never per sample.
    val markerIds = remember(markers) { markers.map { it.id } }

    DisposableEffect(controller) {
        controller.start { styleGeneration++ }
        onDispose {
            binding.save()
            controller.stop()
        }
    }
    DisposableEffect(binding.owner, controller) {
        // The process may be killed after ON_PAUSE; persist the camera while it still can be.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) binding.save()
        }
        binding.owner.lifecycle.addObserver(observer)
        onDispose { binding.owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller, options.darkStyle, options.styleToken) {
        controller.applyStyle(options.darkStyle, options.styleToken)
    }
    LaunchedEffect(controller, options.interactive) { controller.setInteractive(options.interactive) }
    LaunchedEffect(controller, styleGeneration, markers) { controller.setMarkers(markers) }
    LaunchedEffect(controller, styleGeneration, content.path) { controller.setPath(content.path) }
    LaunchedEffect(controller, styleGeneration, options.showMyLocation, camera.mode) {
        controller.updateMyLocation(options.showMyLocation, camera.mode == MapCameraMode.FollowMe)
    }
    LaunchedEffect(controller, styleGeneration, camera, markerIds) {
        controller.applyCamera(camera, latestMarkers.value, content.self)
    }
}
