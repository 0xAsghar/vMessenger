package ir.vmessenger.core.common

/**
 * Read-only view of the running build, so modules that must not depend on the
 * app module (and therefore cannot see its generated `BuildConfig`) can still
 * gate on it — debug-only screens, the About version row, the updater.
 */
interface AppBuildInfo {
    /** True for a debuggable build; never true in a published release APK. */
    val isDebug: Boolean

    /** Marketing version, e.g. `1.0.0`. */
    val versionName: String

    /** Monotonic build number. */
    val versionCode: Long
}
