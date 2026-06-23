package ir.vmessenger.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutRoute() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(R.string.feature_about_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.feature_about_app_name), style = MaterialTheme.typography.headlineSmall)
            Text(text = stringResource(R.string.feature_about_version, "0.1.0-rc1"))
            Text(text = stringResource(R.string.feature_about_description), style = MaterialTheme.typography.bodyMedium)
            Text(text = stringResource(R.string.feature_about_docs_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
