package ir.vmessenger.feature.contacts

import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.LocationSample
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.LocationAccessRepository
import ir.vmessenger.domain.repository.LocationRepository
import ir.vmessenger.domain.usecase.contact.ObserveContactsUseCase
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Everything the detail screen renders about one contact, gathered in a single emission. */
internal data class ContactDetailData(
    val contact: Contact?,
    val canSeeMyLocation: Boolean,
    val localPublicKey: ByteArray?,
    /** Their latest position while they share it with us; null when they do not. */
    val sharedLocation: LocationSample? = null,
    /** What they shared with us inside the retention window, newest first. */
    val history: List<LocationSample> = emptyList(),
)

/**
 * Reads one contact for the detail screen.
 *
 * `observeContacts()` hides blocked rows, which would make the screen vanish the moment the user
 * blocks the contact they are looking at; the direct read is the fallback that keeps it on screen
 * (and returns null only once the contact really is gone, which is how the screen knows to pop).
 */
class ContactDetailSource @Inject constructor(
    private val observeContacts: ObserveContactsUseCase,
    private val contactRepository: ContactRepository,
    private val locationAccess: LocationAccessRepository,
    private val locationRepository: LocationRepository,
    private val getIdentity: GetIdentityUseCase,
) {
    private val localPublicKey: Flow<ByteArray?> = flow { emit(getIdentity()?.ed25519PublicKey) }

    internal fun observe(contactId: String): Flow<ContactDetailData> = combine(
        observeContacts(),
        locationAccess.observeAll(),
        localPublicKey,
        locationRepository.observeIncomingLocations().map { it[contactId] },
        locationRepository.observeSharedHistory(contactId, HISTORY_LIMIT),
    ) { contacts, access, identityKey, location, history ->
        ContactDetailData(
            contact = contacts.firstOrNull { it.id == contactId } ?: contactRepository.getContact(contactId),
            canSeeMyLocation = access[contactId] == true,
            localPublicKey = identityKey,
            sharedLocation = location,
            history = history,
        )
    }

    /** One direct read, for the commands that need the whole contact (resending a request). */
    suspend fun contactOrNull(contactId: String): Contact? = contactRepository.getContact(contactId)

    suspend fun setLocationAccess(contactId: String, granted: Boolean) =
        locationAccess.setAccess(contactId, granted)
}

/** Samples read for the page; retention keeps a day of them, at most 500 per share. */
private const val HISTORY_LIMIT = 1_000
