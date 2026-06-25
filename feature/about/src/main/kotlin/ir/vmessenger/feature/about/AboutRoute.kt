package ir.vmessenger.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.R as DesignR

@Composable
fun AboutRoute(
    onNavigateBack: () -> Unit = {},
) {
    VMessengerScaffold(
        title = stringResource(R.string.feature_about_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(DesignR.drawable.ic_vmessenger_logo),
                contentDescription = stringResource(DesignR.string.vmessenger_logo),
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Text(text = stringResource(R.string.feature_about_app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.feature_about_version, "0.1.0-rc2"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.feature_about_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.feature_about_docs_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
