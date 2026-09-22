package ir.vmessenger.ui.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Content another app handed us through ACTION_SEND, waiting for the user to pick a destination. */
data class SharePayload(val text: String?, val uris: List<String>) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()
}

/**
 * One-shot hand-off of a share from the activity's intent to the picker screen.
 *
 * The payload cannot ride the navigation route: content URIs and arbitrary text do not belong in a
 * back-stack argument that outlives the grant. [consume] empties the store, which also flips the
 * navigation trigger back to null, so a recomposition cannot reopen the picker for a share the user
 * has already been shown.
 */
@Singleton
class PendingShareStore @Inject constructor() {
    private val _pending = MutableStateFlow<SharePayload?>(null)

    val pending: StateFlow<SharePayload?> = _pending.asStateFlow()

    fun set(payload: SharePayload) {
        _pending.value = payload
    }

    fun consume(): SharePayload? {
        val current = _pending.value
        _pending.value = null
        return current
    }
}

/**
 * The share an intent carries, or null when it is not a share or carries nothing usable.
 *
 * Read through `IntentCompat` because the direct getters are deprecated from API 33, and the URIs
 * are kept as strings: the caller copies them through the attachment store, which is the only thing
 * that needs the `Uri` back, and it does so while this activity still holds the read grant.
 */
internal fun sharePayloadOf(intent: Intent?): SharePayload? {
    val uris = when (intent?.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> return null
    }
    val payload = SharePayload(
        text = intent.getStringExtra(Intent.EXTRA_TEXT),
        uris = uris.filterNotNull().map(Uri::toString),
    )
    return payload.takeUnless { it.isEmpty }
}
