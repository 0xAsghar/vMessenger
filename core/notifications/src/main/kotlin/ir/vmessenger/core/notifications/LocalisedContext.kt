package ir.vmessenger.core.notifications

import android.content.Context
import ir.vmessenger.core.common.text.VmLocale
import java.util.Locale

/**
 * The application context, in the language the app is presenting itself in.
 *
 * Needed because AppCompat's per-app language applies to *activity* contexts below Android 13, not
 * to the application context — so a notification built from the injected context would resolve its
 * strings against the **device's** language while every screen used the app's. The shade would
 * disagree with the app that raised it, on exactly the devices that cannot use the platform API.
 *
 * Cheap to call and not cached on purpose: the language can change while the process lives, and a
 * cached context would keep serving the language it was created in.
 */
internal fun Context.localised(): Context {
    val locale = Locale.forLanguageTag(VmLocale.current.tag)
    val configuration = android.content.res.Configuration(resources.configuration).apply {
        setLocale(locale)
    }
    return createConfigurationContext(configuration)
}
