package ir.vmessenger.feature.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.NetworkNode

private const val REPOSITORY_URL = "https://github.com/0xAsghar/vMessenger"
private const val LICENSE_URL = "$REPOSITORY_URL/blob/main/LICENSE"
private const val DOCS_URL = "$REPOSITORY_URL/tree/main/docs"

/**
 * The nodes this build dials, read-only. Managing them — adding, disabling, removing — is the
 * Nodes screen's job, so every row here is inert and the section says where the switches live.
 */
@Composable
internal fun AboutNetworkSection(nodes: AboutNodes) {
    SettingsSection(title = stringResource(R.string.feature_about_section_network)) {
        SettingsRow(
            label = stringResource(R.string.feature_about_row_bootstrap),
            icon = Icons.Outlined.Hub,
            supporting = stringResource(R.string.feature_about_row_bootstrap_body),
            trailing = SettingsTrailing.Text(VmTextFormat.persianDigits(nodes.bootstrap.size.toString())),
        )
        NodeAddresses(nodes = nodes.bootstrap)
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.feature_about_row_relay),
            icon = Icons.Outlined.Router,
            supporting = stringResource(R.string.feature_about_row_relay_body),
            trailing = SettingsTrailing.Text(VmTextFormat.persianDigits(nodes.relay.size.toString())),
        )
        NodeAddresses(nodes = nodes.relay)
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.feature_about_row_nodes_manage),
            supporting = stringResource(R.string.feature_about_row_nodes_manage_body),
            trailing = SettingsTrailing.None,
        )
    }
}

/**
 * The addresses under a role heading. Not [SettingsRow]s: an address is an opaque identifier
 * rather than prose, and under the app's forced RTL its digit groups reorder unless the
 * paragraph is pinned left-to-right — which a row label cannot do.
 */
@Composable
private fun NodeAddresses(nodes: List<NetworkNode>) {
    // Lines the address up under the label of the row above it: row padding, icon, gap.
    val indent = VmSpacing.lg + VmSpacing.xl + VmSpacing.md
    if (nodes.isEmpty()) {
        Text(
            text = stringResource(R.string.feature_about_nodes_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indent, end = VmSpacing.lg, bottom = VmSpacing.md),
        )
        return
    }
    nodes.forEach { node ->
        Text(
            text = node.address,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                // Ltr keeps the digit groups in order; End then resolves against that Ltr
                // paragraph to the right edge, which is where the rows above it start.
                textDirection = TextDirection.Ltr,
                textAlign = TextAlign.End,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indent, end = VmSpacing.lg, bottom = VmSpacing.sm),
        )
    }
}

/** What GPL-3.0 grants, one clause per row, instead of the paragraph this screen used to show. */
@Composable
internal fun AboutLicenseSection() {
    val context = LocalContext.current
    SettingsSection(title = stringResource(R.string.feature_about_section_license)) {
        SettingsRow(
            label = stringResource(R.string.feature_about_row_license),
            icon = Icons.Outlined.Gavel,
            trailing = SettingsTrailing.Text(stringResource(R.string.feature_about_license_name)),
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.feature_about_row_freedom_use),
            supporting = stringResource(R.string.feature_about_row_freedom_use_body),
            trailing = SettingsTrailing.None,
        )
        SettingsRow(
            label = stringResource(R.string.feature_about_row_freedom_share),
            supporting = stringResource(R.string.feature_about_row_freedom_share_body),
            trailing = SettingsTrailing.None,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.feature_about_row_license_text),
            icon = Icons.Outlined.Description,
            supporting = stringResource(R.string.feature_about_row_license_text_body),
            onClick = { openUrl(context, LICENSE_URL) },
        )
    }
}

@Composable
internal fun AboutSourceSection() {
    val context = LocalContext.current
    SettingsSection(title = stringResource(R.string.feature_about_section_source)) {
        SettingsRow(
            label = stringResource(R.string.feature_about_row_repository),
            icon = Icons.Outlined.Code,
            supporting = stringResource(R.string.feature_about_source_url),
            onClick = { openUrl(context, REPOSITORY_URL) },
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.feature_about_row_docs),
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            supporting = stringResource(R.string.feature_about_row_docs_body),
            onClick = { openUrl(context, DOCS_URL) },
        )
    }
}

/**
 * Hands a repository link to whatever browses. A device with nothing that handles `https` is
 * rare but real, and it throws rather than resolving, so the failure stays silent instead of
 * taking the screen down with it.
 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
