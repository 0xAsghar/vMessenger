package ir.vmessenger.feature.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.R as DesignR

/** How much of the primary colour the wash behind the logo keeps at its centre. */
private const val LOGO_WASH_ALPHA = 0.16f

@Composable
fun AboutRoute(
    onNavigateBack: () -> Unit = {},
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val developerModeStatus by viewModel.developerModeStatus.collectAsStateWithLifecycle()
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()

    VMessengerScaffold(
        title = stringResource(R.string.feature_about_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        AboutContent(
            viewModel = viewModel,
            status = developerModeStatus,
            nodes = nodes,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun AboutContent(
    viewModel: AboutViewModel,
    status: DeveloperModeStatus,
    nodes: AboutNodes,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xl),
    ) {
        AboutHeader()
        AboutBuildSection(viewModel = viewModel, status = status)
        AboutNetworkSection(nodes = nodes)
        AboutLicenseSection()
        AboutSourceSection()
    }
}

@Composable
private fun AboutHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        // The logo is the repo's only app-authored vector; the halo behind it is drawn here
        // rather than shipped as a second asset that would need a night variant of its own.
        Box(
            modifier = Modifier
                .size(VmSizes.avatarLg + VmSpacing.xxl)
                .background(
                    brush = Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = LOGO_WASH_ALPHA),
                            Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(DesignR.drawable.ic_vmessenger_logo),
                contentDescription = stringResource(DesignR.string.vmessenger_logo),
                modifier = Modifier.size(VmSizes.avatarLg),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = stringResource(R.string.feature_about_app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.feature_about_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AboutBuildSection(viewModel: AboutViewModel, status: DeveloperModeStatus) {
    SettingsSection(title = stringResource(R.string.feature_about_section_build)) {
        // Seven taps here still toggle developer mode; the countdown rides the supporting
        // line so the row goes on reading as the version row it is.
        SettingsRow(
            label = stringResource(R.string.feature_about_row_version),
            icon = Icons.Outlined.Info,
            supporting = developerModeMessage(status),
            trailing = SettingsTrailing.Text(viewModel.versionName),
            onClick = viewModel::onVersionTapped,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.feature_about_row_build_number),
            icon = Icons.Outlined.Tag,
            trailing = SettingsTrailing.Text(viewModel.versionCode),
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.feature_about_row_protocol),
            icon = Icons.Outlined.Lan,
            supporting = stringResource(R.string.feature_about_row_protocol_body),
            trailing = SettingsTrailing.Text(viewModel.protocolVersion),
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.feature_about_row_database),
            icon = Icons.Outlined.Storage,
            supporting = stringResource(R.string.feature_about_row_database_body),
            trailing = SettingsTrailing.Text(viewModel.databaseVersion),
        )
    }
}

/**
 * The supporting line under the version row, absent while the tap count is [DeveloperModeStatus.Idle]
 * so the row stays a plain version readout until the user is clearly doing it on purpose.
 */
@Composable
private fun developerModeMessage(status: DeveloperModeStatus): String? = when (status) {
    DeveloperModeStatus.Idle -> null
    is DeveloperModeStatus.Countdown -> stringResource(
        R.string.feature_about_developer_mode_countdown,
        VmTextFormat.digits(status.remaining.toString()),
    )
    DeveloperModeStatus.Enabled -> stringResource(R.string.feature_about_developer_mode_enabled)
    DeveloperModeStatus.Disabled -> stringResource(R.string.feature_about_developer_mode_disabled)
}
