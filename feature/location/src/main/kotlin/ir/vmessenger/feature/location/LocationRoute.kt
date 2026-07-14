package ir.vmessenger.feature.location

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.VMessengerScaffold

@Composable
fun LocationRoute(
    viewModel: LocationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Granting only unlocks the feature; sharing still starts with an explicit
    // tap so the button state never changes behind the user's back.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    VMessengerScaffold(
        title = stringResource(R.string.feature_location_title),
    ) { padding ->
        LocationSharingContent(
            state = state,
            hasLocationPermission = hasLocationPermission,
            padding = padding,
            onToggleAccess = viewModel::toggleAccess,
            onToggleSharing = {
                if (hasLocationPermission) {
                    viewModel.toggleSharing()
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun LocationSharingContent(
    state: LocationUiState,
    hasLocationPermission: Boolean,
    padding: PaddingValues,
    onToggleAccess: (String, Boolean) -> Unit,
    onToggleSharing: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LocationMapView(
            samples = state.samples,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(16.dp)),
            showMyLocation = hasLocationPermission,
        )
        Text(
            text = stringResource(R.string.feature_location_access_title),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.contacts, key = { it.contactId }) { item ->
                RowWithCheckbox(
                    label = item.displayName,
                    checked = item.granted,
                    onCheckedChange = { onToggleAccess(item.contactId, it) },
                )
            }
        }
        if (state.hint == LocationHint.SELECT_CONTACT_FIRST) {
            Text(
                text = stringResource(R.string.feature_location_select_contact_first),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = onToggleSharing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = when {
                    !hasLocationPermission -> stringResource(R.string.feature_location_grant_access)
                    state.sharing -> stringResource(R.string.feature_location_stop)
                    else -> stringResource(R.string.feature_location_start)
                },
            )
        }
    }
}

@Composable
private fun RowWithCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
