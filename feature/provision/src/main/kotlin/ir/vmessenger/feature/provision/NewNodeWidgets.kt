package ir.vmessenger.feature.provision

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmRadioButton
import ir.vmessenger.core.designsystem.component.VmSwitch
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

@Composable
internal fun Body(text: String, critical: Boolean = false) {
    VmText(
        text = text,
        style = VmTheme.typography.bodyMd,
        color = if (critical) VmTheme.colors.textCritical else VmTheme.colors.textSecondary,
    )
}

@Composable
internal fun Heading(text: String) {
    VmText(text = text, style = VmTheme.typography.headingSm, color = VmTheme.colors.textPrimary)
}

/** Addresses, ports, fingerprints and logs read left to right in every language. */
@Composable
internal fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

@Composable
internal fun NavButtons(
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
    nextLabel: String = stringResource(R.string.provision_next),
) {
    VmButton(text = nextLabel, onClick = onNext, modifier = Modifier.fillMaxWidth())
    if (onBack != null) {
        VmTextButton(
            text = stringResource(R.string.provision_back),
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = VmSizes.touchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        VmRadioButton(selected = selected, onClick = onClick)
        VmText(text = label, style = VmTheme.typography.bodyLg, color = VmTheme.colors.textPrimary)
    }
}

@Composable
internal fun Toggle(
    label: String,
    supporting: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            VmText(text = label, style = VmTheme.typography.bodyLgMedium, color = VmTheme.colors.textPrimary)
            VmText(text = supporting, style = VmTheme.typography.bodySm, color = VmTheme.colors.textSecondary)
        }
        VmSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A key file of any sane size, read straight into bytes; never into a String. */
internal fun readKeyFile(context: Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(MAX_KEY_BYTES + 1)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read).also { buffer.fill(0) }
    }
    bytes?.takeIf { it.size <= MAX_KEY_BYTES }?.let { (name ?: "key") to it }
}.getOrNull()

private const val MAX_KEY_BYTES = 64 * 1024
