package ir.vmessenger.ui.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import ir.vmessenger.CallActivity
import ir.vmessenger.R

/**
 * Places a call: asks for the microphone if it has not been granted, then dials and opens the
 * call screen.
 *
 * The permission is requested here, at the moment the user asked for a call, rather than at
 * start-up — which is both what the platform advises and the only version of the request that
 * explains itself. A refusal simply does not place the call; nothing is retried behind the user's
 * back, and nothing pretends the call is in progress.
 */
@Composable
internal fun rememberCallLauncher(viewModel: CallViewModel = hiltViewModel()): (String) -> Unit {
    val context = LocalContext.current
    val current = rememberUpdatedState(viewModel)
    var pending by remember { mutableStateOf<String?>(null) }

    val open = remember(context) {
        {
            context.startActivity(
                Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val contactId = pending
        pending = null
        when {
            !granted -> Toast.makeText(context, R.string.call_needs_microphone, Toast.LENGTH_LONG).show()
            contactId != null -> {
                current.value.dial(contactId)
                open()
            }
        }
    }

    return remember(context, request) {
        val place: (String) -> Unit = { contactId ->
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                current.value.dial(contactId)
                open()
            } else {
                pending = contactId
                request.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        place
    }
}
