package ir.vmessenger.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    @Test
    fun parsesTagWithAndWithoutPrefix() {
        assertEquals(SemVer(1, 2, 3, null), SemVer.parse("v1.2.3"))
        assertEquals(SemVer(1, 2, 3, null), SemVer.parse("1.2.3"))
        assertEquals(SemVer(0, 5, 1, null), SemVer.parse("  v0.5.1  "))
    }

    @Test
    fun parsesPreRelease() {
        val parsed = SemVer.parse("v1.0.0-rc1")
        assertEquals(SemVer(1, 0, 0, "rc1"), parsed)
        assertTrue(parsed!!.isPreRelease)
    }

    @Test
    fun ignoresBuildMetadata() {
        assertEquals(SemVer(1, 0, 0, null), SemVer.parse("1.0.0+sha.abc123"))
    }

    @Test
    fun rejectsAnythingThatIsNotOurTagShape() {
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("1.0"))
        assertNull(SemVer.parse("v1.0.0.1"))
        assertNull(SemVer.parse("version-1.0.0"))
        assertNull(SemVer.parse("v1.0.0-"))
        assertNull(SemVer.parse("vx.y.z"))
    }

    @Test
    fun overlongNumbersAreNotParsedRatherThanOverflowing() {
        assertNull(SemVer.parse("1.0.99999999999999999999"))
    }

    @Test
    fun comparesNumericComponents() {
        assertTrue(SemVer.parse("1.0.0")!! > SemVer.parse("0.9.9")!!)
        assertTrue(SemVer.parse("1.10.0")!! > SemVer.parse("1.9.0")!!)
        assertTrue(SemVer.parse("1.0.10")!! > SemVer.parse("1.0.9")!!)
        assertEquals(0, SemVer.parse("1.0.0")!!.compareTo(SemVer.parse("v1.0.0")!!))
    }

    @Test
    fun preReleaseSortsBelowItsOwnRelease() {
        assertTrue(SemVer.parse("1.0.0-rc1")!! < SemVer.parse("1.0.0")!!)
        assertTrue(SemVer.parse("1.0.0-rc1")!! < SemVer.parse("1.0.0-rc2")!!)
        assertTrue(SemVer.parse("1.0.0-alpha")!! < SemVer.parse("1.0.0-beta")!!)
        assertTrue(SemVer.parse("1.0.0-rc")!! < SemVer.parse("1.0.0-rc.1")!!)
        assertTrue(SemVer.parse("1.0.0-1")!! < SemVer.parse("1.0.0-alpha")!!)
    }

    @Test
    fun printsTheMarketingVersion() {
        assertEquals("1.2.3", SemVer.parse("v1.2.3").toString())
        assertEquals("1.2.3-rc1", SemVer.parse("v1.2.3-rc1").toString())
    }

    @Test
    fun newerReleaseIsOffered() {
        assertTrue(SemVer.isNewerRelease("v1.0.0", "0.5.1"))
        assertTrue(SemVer.isNewerRelease("1.0.1", "1.0.0"))
    }

    @Test
    fun sameOrOlderReleaseIsNotOffered() {
        assertFalse(SemVer.isNewerRelease("v1.0.0", "1.0.0"))
        assertFalse(SemVer.isNewerRelease("v0.9.0", "1.0.0"))
    }

    @Test
    fun preReleaseIsNeverOfferedEvenWhenItSortsHigher() {
        assertFalse(SemVer.isNewerRelease("v1.1.0-rc1", "1.0.0"))
        assertFalse(SemVer.isNewerRelease("v2.0.0-beta.2", "1.0.0"))
    }

    @Test
    fun unparseableVersionsAreNeverOffered() {
        assertFalse(SemVer.isNewerRelease("nightly", "1.0.0"))
        assertFalse(SemVer.isNewerRelease("v2.0.0", "not-a-version"))
    }
}
