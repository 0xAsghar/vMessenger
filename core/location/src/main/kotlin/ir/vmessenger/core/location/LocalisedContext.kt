package ir.vmessenger.core.location

import android.content.Context
import ir.vmessenger.core.common.text.VmLocale
import java.util.Locale

/**
 * This context in the language the app presents itself in. AppCompat's per-app language reaches
 * activity contexts only below Android 13, so a service's notification would otherwise use the
 * device's language while the app used its own (the same helper as `core:notifications`).
 */
internal fun Context.localised(): Context {
    val configuration = android.content.res.Configuration(resources.configuration).apply {
        setLocale(Locale.forLanguageTag(VmLocale.current.tag))
    }
    return createConfigurationContext(configuration)
}
