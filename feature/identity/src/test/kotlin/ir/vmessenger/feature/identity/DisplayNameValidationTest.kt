package ir.vmessenger.feature.identity

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameValidationTest {
    @Test
    fun displayNameBoundsMatchOnboardingRules() {
        assertEquals(2, CreateIdentityViewModel.DISPLAY_NAME_MIN)
        assertEquals(32, CreateIdentityViewModel.DISPLAY_NAME_MAX)
    }
}
