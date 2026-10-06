package ir.vmessenger.feature.contacts

import androidx.compose.runtime.saveable.SaverScope
import ir.vmessenger.core.map.MapCameraMode
import ir.vmessenger.core.map.MapCoordinate
import ir.vmessenger.core.map.MapMarker
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactLocationMapTest {
    private fun location(atMs: Long, lat: Double = 35.69, lon: Double = 51.39) = ContactLocation(
        marker = MapMarker("c1", "Ali", "00ff", lat, lon, 5f),
        sampledAtUnixMs = atMs,
        path = persistentListOf(MapCoordinate(35.68, 51.38), MapCoordinate(lat, lon)),
    )

    /** What the activity going through a rotation does to the map's state. */
    private fun rotate(map: ContactLocationMap): ContactLocationMap {
        val saver = ContactLocationMap.Saver
        val saved = checkNotNull(with(saver) { SaverScope { true }.save(map) })
        return checkNotNull(saver.restore(saved))
    }

    @Test
    fun `the map starts in its card, following the contact`() {
        val map = ContactLocationMap()
        assertFalse(map.expanded)
        assertTrue(map.following)
    }

    @Test
    fun `opening the map and shutting it`() {
        val map = ContactLocationMap()
        map.expand()
        assertTrue(map.expanded)
        map.collapse()
        assertFalse(map.expanded)
    }

    @Test
    fun `moving the map stops it following but does not shut it`() {
        val map = ContactLocationMap()
        map.expand()
        map.onUserGesture()
        assertFalse(map.following)
        assertTrue(map.expanded)
    }

    @Test
    fun `shutting a map the person moved centres it on the contact again`() {
        val map = ContactLocationMap()
        map.expand()
        map.onUserGesture()
        map.collapse()
        assertTrue(map.following)
    }

    @Test
    fun `an open map is still open after a rotation, and a shut one is still shut`() {
        assertTrue(rotate(ContactLocationMap().also { it.expand() }).expanded)
        assertFalse(rotate(ContactLocationMap()).expanded)
    }

    @Test
    fun `while following, each new position asks the camera to centre on the contact`() {
        val first = locationMapContent(location(atMs = 10_000), following = true)
        val second = locationMapContent(location(atMs = 20_000, lat = 35.70), following = true)
        assertEquals(MapCameraMode.FitAll, first.camera.mode)
        assertEquals("c1", first.camera.focusId)
        assertNotEquals(first.camera, second.camera)
    }

    @Test
    fun `once the person has moved the map, a new position leaves the camera alone`() {
        val first = locationMapContent(location(atMs = 10_000), following = false)
        val second = locationMapContent(location(atMs = 20_000, lat = 35.70), following = false)
        assertEquals(MapCameraMode.Free, first.camera.mode)
        assertNull(first.camera.focusId)
        // An unchanged request is a camera the map does not touch.
        assertEquals(first.camera, second.camera)
    }

    @Test
    fun `the pin and the route are drawn whether or not the camera follows`() {
        val here = location(atMs = 10_000)
        listOf(true, false).forEach { following ->
            val content = locationMapContent(here, following)
            assertEquals(listOf(here.marker), content.markers)
            assertEquals(here.path, content.path)
        }
    }
}
