package ir.vmessenger.feature.contacts

import ir.vmessenger.domain.model.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationChangesTest {
    private fun sample(at: Long, lat: Double, lon: Double) = LocationSample("s", lat, lon, 5f, at)

    @Test
    fun `a stay is one change, timed when they arrived`() {
        // Newest first: at B since t=40, before that at A since t=10.
        val samples = listOf(
            sample(60, 35.6950, 51.3970),
            sample(50, 35.69501, 51.39701),
            sample(40, 35.6950, 51.3970),
            sample(30, 35.6892, 51.3890),
            sample(20, 35.6892, 51.3890),
            sample(10, 35.6892, 51.3890),
        )
        val changes = locationChanges(samples)
        assertEquals(2, changes.size)
        assertEquals(40L, changes[0].sampledAtUnixMs)
        assertEquals(35.6950, changes[0].latitude, 0.0)
        assertEquals(10L, changes[1].sampledAtUnixMs)
    }

    @Test
    fun `moves farther than the threshold are kept`() {
        val samples = listOf(sample(20, 35.6900, 51.3890), sample(10, 35.6892, 51.3890)) // ~90 m apart
        assertEquals(2, locationChanges(samples).size)
    }
}
