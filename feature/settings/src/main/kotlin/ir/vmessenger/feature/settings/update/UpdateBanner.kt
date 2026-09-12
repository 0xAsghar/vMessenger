package ir.vmessenger.feature.settings.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.feature.settings.R

/**
 * "Version x is available" above the tabs. See [UpdateBannerViewModel] for why "later" only
 * lasts a session.
 */
@Composable
fun UpdateBanner(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpdateBannerViewModel = hiltViewModel(),
) {
    val version by viewModel.availableVersion.collectAsStateWithLifecycle()
    val available = version ?: return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = VmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
        ) {
            Text(
                text = stringResource(
                    R.string.settings_update_banner,
                    VmTextFormat.persianDigits(available),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text(text = stringResource(R.string.settings_update_banner_open)) }
            TextButton(onClick = viewModel::dismiss) {
                Text(text = stringResource(R.string.settings_update_banner_later))
            }
        }
    }
}
