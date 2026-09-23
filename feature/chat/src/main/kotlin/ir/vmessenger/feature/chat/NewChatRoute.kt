package ir.vmessenger.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmSearchBar
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.ContactRelationshipStatus

private const val DISABLED_ALPHA = 0.5f
private const val CONTACT_CONTENT_TYPE = "new-chat-contact"

/**
 * Picks who to talk to. Approved contacts open (or create) their conversation; the rest
 * stay listed but greyed with a status chip, so "why can't I message them" is answered
 * on the spot rather than by their absence.
 */
@Composable
fun NewChatRoute(
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val opened by viewModel.openedConversationId.collectAsStateWithLifecycle()

    LaunchedEffect(opened) {
        opened?.let {
            viewModel.onOpenHandled()
            onOpenConversation(it)
        }
    }

    // Search is a mode here, as it is on the chats and contacts tabs, rather than the permanent
    // state of the screen. It used to be permanent, which meant the scaffold drew a back arrow AND
    // VmSearchBar drew its own identical one right beside it — and the surviving arrow would have
    // been labelled «بستن جستجو» while actually leaving the screen. One arrow at a time now, each
    // saying what it does, and the title finally renders at all.
    var searching by rememberSaveable { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        viewModel.onQueryChange("")
    }
    BackHandler(enabled = searching) { closeSearch() }

    VMessengerScaffold(
        title = stringResource(R.string.feature_chat_new_title),
        onNavigateBack = if (searching) null else onBack,
        actions = {
            if (!searching) {
                VmIconButton(
                    icon = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.feature_chat_search_open),
                    onClick = { searching = true },
                )
            }
        },
        modifier = modifier,
        titleContent = if (!searching) {
            null
        } else {
            {
                VmSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    onClose = closeSearch,
                    placeholder = stringResource(R.string.feature_chat_new_search),
                )
            }
        },
    ) { padding ->
        NewChatContent(
            state = state,
            onNewGroup = onNewGroup,
            onContactClick = viewModel::onContactClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
private fun NewChatContent(
    state: NewChatUiState,
    onNewGroup: () -> Unit,
    onContactClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> SkeletonList(modifier = modifier)

        state.isEmpty -> EmptyState(
            icon = Icons.Outlined.PersonOff,
            title = stringResource(R.string.feature_chat_new_empty_title),
            body = stringResource(R.string.feature_chat_new_empty_body),
            modifier = modifier,
        )

        else -> LazyColumn(modifier = modifier) {
            item(key = "new-group", contentType = "new-group") {
                NewGroupRow(onClick = onNewGroup)
            }
            if (state.isNoResults) {
                item(key = "no-results", contentType = "no-results") {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.feature_chat_no_results_title),
                        body = stringResource(R.string.feature_chat_no_results_body),
                    )
                }
            }
            items(items = state.rows, key = { it.contactId }, contentType = { CONTACT_CONTENT_TYPE }) { row ->
                ContactPickerRow(row = row, onClick = onContactClick)
            }
        }
    }
}

@Composable
private fun NewGroupRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = VmSizes.listItemHeight)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        VmSurface(
            shape = CircleShape,
            color = VmTheme.colors.textAccent,
            contentColor = VmTheme.colors.textOnSolid,
            modifier = Modifier.size(VmSizes.avatarMd),
        ) {
            VmIcon(
                imageVector = Icons.Outlined.Group,
                contentDescription = null,
                modifier = Modifier.padding(VmSpacing.md),
            )
        }
        VmText(
            text = stringResource(R.string.feature_chat_new_group),
            style = VmTheme.typography.bodyLgMedium,
        )
    }
}

@Composable
private fun ContactPickerRow(row: NewChatRow, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.canChat) { onClick(row.contactId) }
            .heightIn(min = VmSizes.listItemHeight)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm)
            .alpha(if (row.canChat) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Avatar(seed = row.seed.bytes, name = row.name, size = VmSizes.avatarMd)
        Column(modifier = Modifier.weight(1f)) {
            VmText(
                text = row.name,
                style = VmTheme.typography.bodyLgMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UserHashText(
                text = row.userHash,
                style = VmTheme.typography.bodySm,
                textAlign = TextAlign.Start,
            )
        }
        StatusChip(row)
    }
}

@Composable
private fun StatusChip(row: NewChatRow) {
    val label = statusLabel(row) ?: return
    VmSurface(
        shape = VmShapes.pill,
        color = VmTheme.colors.bgSubtleStrong,
        contentColor = VmTheme.colors.textSecondary,
    ) {
        VmText(
            text = label,
            style = VmTheme.typography.bodyXsMedium,
            modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xxs),
        )
    }
}

@Composable
private fun statusLabel(row: NewChatRow): String? = when {
    row.blocked -> stringResource(R.string.feature_chat_status_blocked)
    row.status == ContactRelationshipStatus.PENDING_OUT ->
        stringResource(R.string.feature_chat_status_pending_out)

    row.status == ContactRelationshipStatus.PENDING_IN ->
        stringResource(R.string.feature_chat_status_pending_in)

    row.status == ContactRelationshipStatus.REJECTED -> stringResource(R.string.feature_chat_status_rejected)
    else -> null
}
