package ir.vmessenger.feature.map

import androidx.compose.runtime.Immutable
import ir.vmessenger.core.map.CameraRequest
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Whether this app may read the device position, and whether asking again is still possible. */
enum class MapPermission { Granted, Denied, PermanentlyDenied }

/** One-shot explanations shown in the sheet. */
enum class MapHint { SelectContactFirst }

@Immutable
data class MapPoint(val latitude: Double, val longitude: Double)

/** A contact currently sharing their position with us. Every label is already formatted. */
@Immutable
data class ContactMarker(
    val contactId: String,
    val name: String,
    /** Hex of the identity hash: the identicon and its colour are derived from it. */
    val seedHex: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    /** `۲ دقیقه پیش` — computed once in the view model, never in composition. */
    val lastUpdateLabel: String,
    /** Metres to this device, or null while we have no fix of our own. */
    val distanceM: Float?,
)

/** An approved contact and whether they may see our position. */
@Immutable
data class ContactAccess(
    val contactId: String,
    val name: String,
    val seedHex: String,
    val granted: Boolean,
)

@Immutable
data class SharingState(
    val active: Boolean = false,
    val grantedNames: ImmutableList<String> = persistentListOf(),
)

@Immutable
data class MapUiState(
    val permission: MapPermission = MapPermission.Denied,
    val sharing: SharingState = SharingState(),
    val markers: ImmutableList<ContactMarker> = persistentListOf(),
    val contacts: ImmutableList<ContactAccess> = persistentListOf(),
    val myLocation: MapPoint? = null,
    val camera: CameraRequest = CameraRequest(),
    val selectedContactId: String? = null,
    val tilesError: Boolean = false,
    /** Bumped by "try again" on the offline banner; forces the style to be fetched afresh. */
    val styleToken: Int = 0,
    val hint: MapHint? = null,
)

/**
 * The identicon seed as bytes. Kept out of the state itself: a `ByteArray` compares by identity,
 * which would make every marker list look "changed" and force the map to redraw on every sample.
 */
internal fun String.toSeedBytes(): ByteArray = runCatching {
    ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}.getOrDefault(ByteArray(0))
