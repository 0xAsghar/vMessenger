package ir.vmessenger.core.datastore

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

fun ThemeMode.toDarkTheme(isSystemDark: Boolean): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemDark
}
