package ir.vmessenger.feature.debug

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.common.logging.LogLevel
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmButtonSize
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import java.io.File

@Composable
fun LogsRoute(
    onNavigateBack: () -> Unit = {},
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.feature_logs_copied)

    VMessengerScaffold(
        title = stringResource(R.string.feature_logs_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
            ) {
                VmButton(
                    text = stringResource(R.string.feature_logs_export_share),
                    onClick = { shareLogFile(context, viewModel.snapshotText()) },
                    size = VmButtonSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                VmOutlinedButton(
                    text = stringResource(R.string.feature_logs_copy),
                    onClick = {
                        clipboard.setText(AnnotatedString(viewModel.snapshotText()))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    },
                    size = VmButtonSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                VmOutlinedButton(
                    text = stringResource(R.string.feature_logs_clear),
                    onClick = { viewModel.clear() },
                    size = VmButtonSize.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (entries.isEmpty()) {
                VmText(
                    text = stringResource(R.string.feature_logs_empty),
                    color = VmTheme.colors.textSecondary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(VmSpacing.xs),
                ) {
                    items(entries, key = { "${it.timestampUnixMs}-${it.tag}-${it.message}" }) { entry ->
                        VmText(
                            text = entry.formatLine(),
                            style = VmTheme.typography.bodySm.copy(fontFamily = FontFamily.Monospace),
                            color = when (entry.level) {
                                LogLevel.ERROR -> VmTheme.colors.textCritical
                                LogLevel.WARN -> VmTheme.colors.textWarning
                                else -> VmTheme.colors.textPrimary
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun shareLogFile(context: android.content.Context, text: String) {
    // Only cacheDir/log-export is exposed through the FileProvider (see app/res/xml/file_paths.xml).
    val exportDir = File(context.cacheDir, "log-export").apply { mkdirs() }
    val file = File(exportDir, "vmessenger-export-${System.currentTimeMillis()}.log")
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.feature_logs_share_chooser)))
}
