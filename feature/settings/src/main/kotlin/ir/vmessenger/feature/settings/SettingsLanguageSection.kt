package ir.vmessenger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.common.text.VmLocale
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.VmChip
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * The language picker.
 *
 * Each language is named in itself — «فارسی», "English" — in both translations, which is what every
 * picker does and the only version that is readable to someone who has ended up in a language they
 * cannot read and wants to get back.
 *
 * The state and the setter arrive as parameters rather than from a ViewModel here, the same way the
 * PIN dialog does: the per-app language API is AppCompat's and lives in `:app`, so this module does
 * not depend on it. Choosing a language recreates the activity, so there is nothing to confirm and
 * nothing to restart.
 */
@Composable
internal fun SettingsLanguageSection(language: VmLocale, onLanguage: (VmLocale) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_language)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
        ) {
            VmLocale.entries.forEach { locale ->
                VmChip(
                    selected = language == locale,
                    onClick = { onLanguage(locale) },
                    label = stringResource(locale.labelRes()),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun VmLocale.labelRes(): Int = when (this) {
    VmLocale.Fa -> R.string.settings_language_fa
    VmLocale.En -> R.string.settings_language_en
}
