package ir.vmessenger.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmDivider
import ir.vmessenger.core.designsystem.component.VmDropdownMenu
import ir.vmessenger.core.designsystem.component.VmDropdownMenuItem
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.data.activity.ActivityLogFormat
import java.io.File

/**
 * The user's own record of what they did to this app.
 *
 * Exportable by the person it is about, in the format whatever they want to read it with expects.
 * The file goes to the app's own cache directory and out through the same FileProvider the debug
 * log export uses; nothing is uploaded anywhere.
 */
@Composable
fun ActivityLogRoute(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val pendingExport by viewModel.export.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(pendingExport) {
        val ready = pendingExport ?: return@LaunchedEffect
        shareActivityLog(context, ready)
        viewModel.onExportHandled()
    }

    val listState = rememberLazyListState()
    VMessengerScaffold(
        title = stringResource(R.string.feature_settings_activity_title),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        scrolled = listState.canScrollBackward,
        actions = {
            ExportMenu(onPick = viewModel::requestExport)
            VmIconButton(
                icon = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.feature_settings_activity_clear),
                onClick = { confirmClear = true },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.feature_settings_activity_empty_title),
                body = stringResource(R.string.feature_settings_activity_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize()) {
                items(entries, key = { it.id }) { row ->
                    ActivityRow(row)
                    VmDivider(modifier = Modifier.padding(horizontal = VmSpacing.lg))
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.feature_settings_activity_clear_title),
            body = stringResource(R.string.feature_settings_activity_clear_body),
            confirmLabel = stringResource(R.string.feature_settings_activity_clear),
            onConfirm = {
                confirmClear = false
                viewModel.clear()
            },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
}

@Composable
private fun ExportMenu(onPick: (ActivityLogFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // The menu anchors to what it shares a box with: the button, not the whole bar.
    Box {
        VmIconButton(
            icon = Icons.Outlined.Share,
            contentDescription = stringResource(R.string.feature_settings_activity_export),
            onClick = { open = true },
        )
        VmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActivityLogFormat.entries.forEach { format ->
                VmDropdownMenuItem(
                    text = format.extension.uppercase(),
                    onClick = {
                        open = false
                        onPick(format)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActivityRow(row: ActivityLogRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
    ) {
        VmText(
            text = stringResource(row.kind.labelRes()),
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textPrimary,
        )
        row.detail?.let { detail ->
            VmText(
                text = detail,
                style = VmTheme.typography.bodySm,
                color = VmTheme.colors.textSecondary,
            )
        }
        VmText(
            text = VmDateFormat.dayAndTime(row.atUnixMs),
            style = VmTheme.typography.bodyXs,
            color = VmTheme.colors.textSecondary,
        )
    }
}

/**
 * Writes the rendered log to the cache directory and offers it to the share sheet.
 *
 * Only `cacheDir/log-export` is exposed through the FileProvider (see `app/res/xml/file_paths.xml`),
 * which is the same path the debug log export already uses.
 */
private fun shareActivityLog(context: Context, ready: ActivityLogExportReady) {
    val exportDir = File(context.cacheDir, "log-export").apply { mkdirs() }
    val file = File(exportDir, "vmessenger-activity-${System.currentTimeMillis()}.${ready.format.extension}")
    file.writeText(ready.content)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = ready.format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.feature_settings_activity_export)),
    )
}
