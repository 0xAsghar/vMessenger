package ir.vmessenger.app.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.text.VmLocale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bridge between AppCompat's per-app language and [VmLocale], which is what the formatters read.
 *
 * Two stores, unavoidably. Resource lookups resolve through the Android configuration AppCompat
 * manages; the digit, date and unit formatters are pure Kotlin and read [VmLocale]. [sync] is what
 * keeps them from disagreeing, and it runs at process start — before any screen or notification is
 * built — because a formatter reading the wrong language is not a crash, it is a screen of Persian
 * digits inside English sentences that nobody notices in review.
 *
 * `AppCompatDelegate` rather than the platform's `LocaleManager`: that API arrived in Android 13 and
 * this app supports 26.
 */
@Singleton
class AppLocaleController @Inject constructor() {
    /** Publishes whatever language the app is currently configured for to [VmLocale]. */
    fun sync() {
        val stored = AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotBlank() }
        VmLocale.current = VmLocale.of(stored)
        AppLogger.info(TAG, "app language is ${VmLocale.current.tag}")
    }

    /**
     * Switches language. AppCompat recreates the activity, so the new value is set here as well as
     * stored: the recreation reads [VmLocale] on its way up, and it must already be right.
     */
    fun set(locale: VmLocale) {
        VmLocale.current = locale
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.tag))
        AppLogger.info(TAG, "app language set to ${locale.tag}")
    }

    private companion object {
        const val TAG = "Locale"
    }
}
