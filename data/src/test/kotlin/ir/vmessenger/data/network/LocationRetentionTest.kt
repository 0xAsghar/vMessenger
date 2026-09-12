package ir.vmessenger.data.network

import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import ir.vmessenger.core.database.entity.MessageDirection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRetentionTest {
    private val now = 1_700_000_000_000L
    private val harness = CleanupHarness()

    private fun share(id: String, active: Boolean, endedAt: Long? = null) = LocationShareEntity(
        shareId = id,
        contactId = "a",
        direction = MessageDirection.INCOMING,
        active = active,
        startedAtUnixMs = now - 2 * LocationSharingCoordinator.RETENTION_WINDOW_MS,
        endedAtUnixMs = endedAt,
    )

    private fun sample(shareId: String, sampledAt: Long) = LocationSampleEntity(
        shareId = shareId,
        latitude = 35.7,
        longitude = 51.4,
        accuracyM = 5f,
        speedMps = null,
        headingDeg = null,
        batteryPct = null,
        sampledAtUnixMs = sampledAt,
    )

    @Test
    fun purgeKeepsRecentAndLastN() = runTest {
        val keep = LocationSharingCoordinator.MAX_SAMPLES_PER_SHARE
        val window = LocationSharingCoordinator.RETENTION_WINDOW_MS
        harness.shareDao.shares += share("s1", active = true)
        harness.shareDao.shares += share("s2", active = true)
        // s1: more recent samples than the cap, plus some outside the window.
        repeat(keep + 100) { harness.sampleDao.insert(sample("s1", now - it * 1_000L)) }
        repeat(5) { harness.sampleDao.insert(sample("s1", now - window - (it + 1) * 60_000L)) }
        // s2: a handful of recent samples stays untouched.
        repeat(3) { harness.sampleDao.insert(sample("s2", now - it * 1_000L)) }

        harness.locationSharing.runRetention(now)

        val s1 = harness.sampleDao.forShare("s1")
        assertEquals(keep, s1.size)
        assertTrue(s1.all { it.sampledAtUnixMs >= now - window })
        // The newest survive: nothing older than the (keep)th most recent one.
        assertEquals(now - (keep - 1) * 1_000L, s1.minOf { it.sampledAtUnixMs })
        assertEquals(3, harness.sampleDao.forShare("s2").size)
    }

    @Test
    fun endedSharesRemovedAfterWindow() = runTest {
        val window = LocationSharingCoordinator.RETENTION_WINDOW_MS
        harness.shareDao.shares += share("old", active = false, endedAt = now - window - 1)
        harness.shareDao.shares += share("recent", active = false, endedAt = now - window + 60_000L)
        harness.shareDao.shares += share("live", active = true)

        harness.locationSharing.runRetention(now)

        assertEquals(listOf("recent", "live"), harness.shareDao.shares.map { it.shareId })
    }
}
