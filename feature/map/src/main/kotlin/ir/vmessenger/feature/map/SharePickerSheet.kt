package ir.vmessenger.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.component.VmModalSheet
import ir.vmessenger.core.designsystem.component.VmSwitch
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import kotlinx.collections.immutable.ImmutableList

private val PICKER_MAX_HEIGHT = 440.dp

/** "Who may see me": one switch per approved contact, written straight through to the repository. */
@Composable
internal fun SharePickerSheet(
    contacts: ImmutableList<ContactAccess>,
    onSetAccess: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    VmModalSheet(onDismissRequest = onDismiss) {
        SectionHeader(title = stringResource(R.string.feature_map_picker_title))
        if (contacts.isEmpty()) {
            VmText(
                text = stringResource(R.string.feature_map_picker_empty),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT)) {
            items(contacts, key = { it.contactId }) { contact ->
                AccessRow(contact = contact, onSetAccess = onSetAccess)
            }
        }
    }
}

@Composable
private fun AccessRow(contact: ContactAccess, onSetAccess: (String, Boolean) -> Unit) {
    val seed = remember(contact.seedHex) { contact.seedHex.toSeedBytes() }
    // One toggle per contact: the whole row flips it, and the switch only shows its state.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = contact.granted,
                role = Role.Switch,
                onValueChange = { granted -> onSetAccess(contact.contactId, granted) },
            )
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Avatar(seed = seed, name = contact.name, size = VmSizes.avatarSm)
        VmText(
            text = contact.name,
            style = VmTheme.typography.bodyLg,
            color = VmTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        VmSwitch(checked = contact.granted, onCheckedChange = null)
    }
}
