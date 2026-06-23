package ir.vmessenger.core.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun lightMode_isNotDark() {
        assertFalse(ThemeMode.LIGHT.toDarkTheme(isSystemDark = true))
    }

    @Test
    fun darkMode_isDark() {
        assertTrue(ThemeMode.DARK.toDarkTheme(isSystemDark = false))
    }

    @Test
    fun systemMode_followsSystem() {
        assertTrue(ThemeMode.SYSTEM.toDarkTheme(isSystemDark = true))
        assertFalse(ThemeMode.SYSTEM.toDarkTheme(isSystemDark = false))
    }
}
