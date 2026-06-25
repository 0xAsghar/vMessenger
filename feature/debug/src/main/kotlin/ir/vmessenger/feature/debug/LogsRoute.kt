package ir.vmessenger.feature.debug

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.common.logging.LogLevel
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import java.io.File

@Composable
fun LogsRoute(
    onNavigateBack: () -> Unit = {},
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current

    VMessengerScaffold(
        title = stringResource(R.string.feature_logs_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        shareLogFile(context, viewModel.snapshotText())
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.feature_logs_export_share))
                }
                OutlinedButton(
                    onClick = { viewModel.clear() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.feature_logs_clear))
                }
            }
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.feature_logs_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries, key = { "${it.timestampUnixMs}-${it.tag}-${it.message}" }) { entry ->
                        Text(
                            text = entry.formatLine(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = when (entry.level) {
                                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onBackground
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun shareLogFile(context: android.content.Context, text: String) {
    val file = File(context.cacheDir, "vmessenger-export-${System.currentTimeMillis()}.log")
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.feature_logs_share_chooser)))
}
