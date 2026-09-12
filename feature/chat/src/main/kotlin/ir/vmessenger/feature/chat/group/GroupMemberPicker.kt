package ir.vmessenger.feature.chat.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.feature.chat.IdentitySeed
import ir.vmessenger.feature.chat.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import java.text.Collator
import java.util.Locale

private const val DISABLED_ALPHA = 0.4f
private const val PICKER_CONTENT_TYPE = "group-picker-contact"
private const val SHEET_HEIGHT_FRACTION = 0.9f

/**
 * The ceilings the group screens apply before the repository has to.
 *
 * They live with the picker because the member cap is the rule it exists to enforce: the
 * user is told "no more than this" while choosing, not after a fan-out has already begun.
 */
object GroupLimits {

    /** The user plus [MAX_OTHER_MEMBERS]; a membership snapshot has to stay one small message. */
    const val MAX_MEMBERS = 32

    /** How many people may be picked besides the user themselves. */
    const val MAX_OTHER_MEMBERS = MAX_MEMBERS - 1

    const val MAX_NAME_LENGTH = 64

    /** Whitespace is not a name, and the snapshot caps the rest at [MAX_NAME_LENGTH]. */
    fun isValidName(raw: String): Boolean = raw.trim().length in 1..MAX_NAME_LENGTH
}

/** One approved contact the picker can offer. */
@Immutable
data class GroupPickerContact(
    val contactId: String,
    val name: String,
    val userHash: String,
    val seed: IdentitySeed,
)

/**
 * Everything the picker draws, the cap included.
 *
 * [capacity] is not always [GroupLimits.MAX_OTHER_MEMBERS]: adding to an existing group only
 * has room for whatever the current membership left over, so the ceiling is passed in rather
 * than assumed.
 */
@Immutable
data class GroupPickerState(
    val contacts: ImmutableList<GroupPickerContact> = persistentListOf(),
    val selected: ImmutableSet<String> = persistentSetOf(),
    val query: String = "",
    val capacity: Int = GroupLimits.MAX_OTHER_MEMBERS,
    val loading: Boolean = true,
) {
    val selectedCount: Int get() = selected.size

    val hasSelection: Boolean get() = selected.isNotEmpty()

    /** Nothing further may be ticked; rows already ticked still untick. */
    val atCapacity: Boolean get() = selectedCount >= capacity

    val visible: ImmutableList<GroupPickerContact>
        get() = contacts.filter { it.matches(query) }.toImmutableList()

    /** There is nobody to pick at all, as opposed to a search that matched nobody. */
    val isEmpty: Boolean get() = !loading && contacts.isEmpty()

    val isNoResults: Boolean get() = !loading && contacts.isNotEmpty() && visible.isEmpty()

    fun canSelect(contactId: String): Boolean = contactId in selected || !atCapacity
}

/**
 * Ticking a contact off always works; ticking one on is refused once [capacity] is reached.
 *
 * Both group flows share this so the cap is one rule, applied where the tap happens instead of
 * being trimmed silently somewhere further down.
 */
internal fun ImmutableSet<String>.toggleWithin(capacity: Int, contactId: String): ImmutableSet<String> = when {
    contactId in this -> (this - contactId).toPersistentSet()
    size >= capacity -> this
    else -> (this + contactId).toPersistentSet()
}

/**
 * Approved, unblocked contacts in the order a Persian reader expects.
 *
 * Anyone else is left out rather than greyed: a group message to a contact who has not approved
 * us could not be delivered, so offering them would only produce a failure later.
 */
internal fun List<Contact>.toPickerContacts(): List<GroupPickerContact> {
    val collator = Collator.getInstance(Locale.forLanguageTag("fa"))
    return filter { it.isApproved && !it.blocked }
        .map {
            GroupPickerContact(
                contactId = it.id,
                name = it.displayName,
                userHash = it.userHash,
                seed = IdentitySeed(it.identityHash),
            )
        }
        .sortedWith { left, right -> collator.compare(left.name, right.name) }
}

/**
 * The approved-contact list shared by "new group" and "add members": a search field, tick
 * boxes, a running count and — only once it bites — the line that explains why the remaining
 * rows went flat.
 */
@Composable
fun GroupMemberPicker(
    state: GroupPickerState,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PickerSearchField(query = state.query, onQueryChange = onQueryChange)
        PickerCounter(state = state)
        PickerList(state = state, onToggle = onToggle, modifier = Modifier.weight(1f))
    }
}

/**
 * "Add members" over the group info screen: the same picker plus the button that commits it.
 * Nothing is sent until that button is pressed, so a mis-tap costs nothing.
 */
@Composable
fun GroupMemberPickerSheet(
    state: GroupPickerState,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxHeight(SHEET_HEIGHT_FRACTION)) {
            Text(
                text = stringResource(R.string.feature_chat_group_add_members_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
            )
            GroupMemberPicker(
                state = state,
                onQueryChange = onQueryChange,
                onToggle = onToggle,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onConfirm,
                enabled = state.hasSelection,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(VmSpacing.lg),
            ) {
                Text(text = stringResource(R.string.feature_chat_group_add_members_confirm))
            }
        }
    }
}

@Composable
private fun PickerSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(text = stringResource(R.string.feature_chat_group_picker_search)) },
        leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.feature_chat_group_picker_clear),
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
    )
}

@Composable
private fun PickerCounter(state: GroupPickerState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xxs),
    ) {
        Text(
            text = stringResource(
                R.string.feature_chat_group_picker_selected,
                VmTextFormat.persianDigits(state.selectedCount.toString()),
                VmTextFormat.persianDigits(state.capacity.toString()),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        // Only shown once the cap actually bites: before that the ceiling is noise, and
        // after it the greyed-out rows below need an explanation on the spot.
        if (state.atCapacity) {
            Text(
                text = stringResource(
                    R.string.feature_chat_group_picker_cap,
                    VmTextFormat.persianDigits(GroupLimits.MAX_MEMBERS.toString()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PickerList(
    state: GroupPickerState,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(state.contacts, state.query) { state.visible }
    when {
        state.loading -> SkeletonList(modifier = modifier)

        state.isEmpty -> EmptyState(
            icon = Icons.Outlined.PersonOff,
            title = stringResource(R.string.feature_chat_group_picker_empty_title),
            body = stringResource(R.string.feature_chat_group_picker_empty_body),
            modifier = modifier,
        )

        state.isNoResults -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.feature_chat_no_results_title),
            body = stringResource(R.string.feature_chat_no_results_body),
            modifier = modifier,
        )

        else -> LazyColumn(modifier = modifier) {
            items(items = rows, key = { it.contactId }, contentType = { PICKER_CONTENT_TYPE }) { contact ->
                PickerRow(
                    contact = contact,
                    checked = contact.contactId in state.selected,
                    enabled = state.canSelect(contact.contactId),
                    onToggle = onToggle,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    contact: GroupPickerContact,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle(contact.contactId) },
            )
            .heightIn(min = VmSizes.listItemHeight)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        // The row owns the toggle semantics, so the box itself is decorative.
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Avatar(seed = contact.seed.bytes, name = contact.name, size = VmSizes.avatarMd)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UserHashText(
                text = contact.userHash,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/** Name or user hash, ignoring case and the dashes the hash is printed with. */
private fun GroupPickerContact.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return name.lowercase().contains(needle) ||
        userHash.replace("-", "").lowercase().contains(needle.replace("-", ""))
}
