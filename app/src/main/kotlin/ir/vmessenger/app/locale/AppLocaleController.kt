package ir.vmessenger.app.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.text.VmLocale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the app's language in one place, and keeps two stores from ever disagreeing.
 *
 * Resource lookups resolve through the Android configuration; the digit, date and unit formatters
 * are pure Kotlin and read [VmLocale]. When those two disagree the result is not a crash but a
 * screen of English sentences laid out right to left with Persian digits in them — which is exactly
 * what every English-language phone showed on first launch before this class held the rule below.
 *
 * **The policy:** the app shows the per-app language when one has been chosen, and Persian
 * otherwise. Not the device's language: a phone set to English is the ordinary case here, and
 * everyone who used 1.1.2 on one saw Persian. So the first launch pins Persian explicitly, and the
 * device language only ever wins if the user deliberately picks "system default" for this app in
 * Android's own settings.
 *
 * **The invariant:** [onActivityConfiguration] sets [VmLocale] from the configuration the activity
 * actually received, so the formatters follow whatever the resources resolved to rather than what
 * we believe was requested. That is what makes a disagreement impossible rather than unlikely.
 *
 * **One store.** The choice lives in a small SharedPreferences file of this class's own — readable
 * synchronously from `Application.onCreate`, including in a process a background service started
 * before any activity exists — and nowhere else below Android 13. AppCompat's `autoStoreLocales` is
 * deliberately not used: it keeps a second copy, and on its first run on Android 13+ it "migrates"
 * that copy to the platform, which on a fresh install means writing an empty list over the Persian
 * pin made moments earlier. Two stores and a migration between them was the bug; this is AppCompat's
 * documented manual mode instead — persist it yourself, re-apply it at every start.
 */
@Singleton
class AppLocaleController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Set on the first launch on Android 13+, cleared by the first activity handing the pin over.
     *
     * The pin cannot be made from `Application.onCreate`. It persists from there, but the activity
     * already being launched keeps the device's configuration, and nothing delivers the change to
     * it — recreating it by hand produced another English instance, and then another, five thousand
     * times over. Made from inside an activity it is the ordinary in-app language change, which the
     * platform answers by recreating that activity in the new language itself.
     */
    @Volatile
    private var pendingPin = false

    /** Process start. Decides the language before any screen or notification is built. */
    fun sync() {
        val mirrored = prefs.getString(KEY_LOCALE, null)
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            syncPlatform(mirrored)
        } else {
            syncCompat(mirrored)
        }
        VmLocale.current = locale
        if (!pendingPin) prefs.edit { putString(KEY_LOCALE, locale.tag) }
        AppLogger.info(TAG, "app language is ${locale.tag}")
    }

    /** Android 13+: the platform holds the choice, including one made in system settings. */
    private fun syncPlatform(mirrored: String?): VmLocale {
        val platform = platformLocales()
        return when {
            platform != null -> VmLocale.of(platform)
            // Launched before, and the choice was since cleared in system settings: the user asked
            // for the device language, so that is what the resources will use.
            mirrored != null -> VmLocale.of(deviceLanguage())
            // First launch, including the first launch after upgrading from 1.1.2. Decided here,
            // handed to the platform by the first activity — see [pendingPin].
            else -> VmLocale.Fa.also { pendingPin = true }
        }
    }

    /**
     * Below 13 nothing but this class persists the choice, so it is handed to AppCompat on every
     * start; AppCompat applies it to each activity as the activity is created.
     */
    private fun syncCompat(mirrored: String?): VmLocale {
        val locale = mirrored?.let(VmLocale::of) ?: pin(VmLocale.Fa)
        apply(locale)
        return locale
    }

    /**
     * Called by every activity once its configuration is final.
     *
     * On the first launch on Android 13+ this is where the Persian pin is handed to the platform —
     * see [pendingPin] — and the platform recreates the activity; nothing here ever recreates one.
     * Otherwise whatever the framework applied is the truth (a change made in system settings, the
     * choice AppCompat applied below 13) and [VmLocale] is corrected to match it.
     */
    fun onActivityConfiguration(configuration: Configuration) {
        if (pendingPin) {
            pendingPin = false
            pin(VmLocale.Fa)
            // Not mirrored yet: this instance is about to be replaced by one built in Persian, and
            // that one records it. Mirroring this instance's language would record the device's.
            return
        }
        val applied = VmLocale.of(configuration.locales.get(0)?.language)
        if (applied != VmLocale.current) {
            AppLogger.info(TAG, "app language corrected from ${VmLocale.current.tag} to ${applied.tag}")
        }
        VmLocale.current = applied
        prefs.edit { putString(KEY_LOCALE, applied.tag) }
    }

    /**
     * Switches language. AppCompat recreates the activity, so the new value is set here as well as
     * stored: the recreation reads [VmLocale] on its way up, and it must already be right.
     */
    fun set(locale: VmLocale) {
        VmLocale.current = locale
        prefs.edit { putString(KEY_LOCALE, locale.tag) }
        apply(locale)
        AppLogger.info(TAG, "app language set to ${locale.tag}")
    }

    private fun pin(locale: VmLocale): VmLocale {
        apply(locale)
        AppLogger.info(TAG, "no app language chosen yet; pinning ${locale.tag}")
        return locale
    }

    /**
     * Hands the choice to whichever layer actually owns it.
     *
     * Android 13+ goes to the platform `LocaleManager` directly, not through AppCompat. AppCompat
     * finds the manager through an *active activity's* delegate, so from `Application.onCreate` —
     * where the first-launch pin happens — its setter was silently a no-op: every English-language
     * phone kept resolving English resources while this class believed it had pinned Persian.
     * Below 13 AppCompat applies it to each activity as it is created; without `autoStoreLocales`
     * it keeps nothing, which is why [syncCompat] hands it over at every start.
     */
    private fun apply(locale: VmLocale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                LocaleList.forLanguageTags(locale.tag)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.tag))
        }
    }

    /**
     * The per-app language Android 13+ holds; null below it, or when none is set. Read from the
     * platform for the same reason [apply] writes to it: through AppCompat this read came back
     * empty before the first activity existed.
     */
    private fun platformLocales(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        return locales?.toLanguageTags()?.takeIf { it.isNotBlank() }
    }

    private fun deviceLanguage(): String? =
        context.resources.configuration.locales.get(0)?.language

    private companion object {
        const val TAG = "Locale"
        const val PREFS = "app_locale"
        const val KEY_LOCALE = "locale"
    }
}
