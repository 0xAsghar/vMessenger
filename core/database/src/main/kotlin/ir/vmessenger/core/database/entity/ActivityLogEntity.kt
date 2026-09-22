package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What the user did to this app.
 *
 * The line this table holds to, and the reason it is safe to export: **it records what the user did
 * to the app, not who they communicated with.** Unlocking, adding a node, granting a permission,
 * starting a location share — those are the user's own actions on their own installation. Message
 * bodies are never here, and neither is the other party to a call or a conversation: a log that
 * named them would turn a diagnostics record into a contact graph that leaves the device the moment
 * it is exported.
 *
 * Contacts are the one place a name appears, and only for an action the user took deliberately on
 * someone already listed in their own contacts — adding or blocking them.
 *
 * Device-local with no foreign key. It is a record of events, and an event does not stop having
 * happened because the row it referred to is gone. Not backed up.
 */
@Entity(tableName = "activity_log", indices = [Index("atUnixMs")])
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ActivityKind,
    /**
     * One short, non-sensitive particular: a node address, a permission name, a contact's display
     * name, an error code. Never message text, and never a peer of a call or a chat.
     */
    val detail: String?,
    val atUnixMs: Long,
)

/**
 * The kinds of event worth recording.
 *
 * Deliberately coarse. A log with a value for every code path becomes a behavioural trace; these
 * are the events a user would want to be able to check after the fact — did the app lock, did it
 * reach the network, what did I grant, when was this account wiped.
 */
enum class ActivityKind {
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

    /** That a call happened, and which way it went. Never with whom; see [ActivityLogEntity]. */
    CallPlaced,
    CallReceived,
    CallEnded,
    ContactAdded,
    ContactBlocked,
    AccountWiped,

    /** A failure the user might otherwise only experience as "it did not work". */
    Failure,
}
