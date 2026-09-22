package ir.vmessenger.domain.model

/**
 * One event from this device's own activity log.
 *
 * The domain mirror of the stored row, so the screens that read the log do not need the database
 * layer on their classpath. The rule the log holds to is written on the entity behind it: it
 * records what the user did to the app, never who they communicated with.
 */
data class ActivityEvent(
    val id: Long,
    val kind: ActivityEventKind,
    /** One short, non-sensitive particular: a node address, a permission name, a contact's name. */
    val detail: String?,
    val atUnixMs: Long,
)

/** Deliberately coarse; see [ActivityEvent]. */
enum class ActivityEventKind {
    IdentityCreated,
    AppUnlocked,
    AppLocked,
    NodeAdded,
    NodeRemoved,
    NetworkConnected,
    NetworkDisconnected,
    PermissionGranted,
    PermissionDenied,
    LocationSharingStarted,
    LocationSharingStopped,
    CallPlaced,
    CallReceived,
    CallEnded,
    ContactAdded,
    ContactBlocked,
    AccountWiped,
    Failure,
}
