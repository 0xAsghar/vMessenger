package ir.vmessenger.feature.contacts

import ir.vmessenger.domain.model.ContactRelationshipStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsStateTest {

    @Test
    fun `search matches the display name regardless of case`() {
        assertTrue(row(name = "Sara").matches("sar"))
    }

    @Test
    fun `search matches a user hash typed without its dashes`() {
        assertTrue(row(userHash = "vm2-ABCD-EFGH").matches("abcdefgh"))
    }

    @Test
    fun `search that matches nothing leaves the list empty but not the empty state`() {
        val state = buildContactsState(
            rows = listOf(row(name = "Sara")),
            requests = emptyList(),
            control = ContactsControl(query = "zzz"),
        )
        assertTrue(state.contacts.isEmpty())
        assertTrue(state.noSearchResults)
        assertTrue(!state.isEmpty)
    }

    @Test
    fun `a pending delete resolves to a dialog naming the contact`() {
        val state = buildContactsState(
            rows = listOf(row(id = "c1", name = "Sara")),
            requests = emptyList(),
            control = ContactsControl(pending = PendingContactAction.Delete("c1")),
        )
        assertEquals(ContactDialog.Delete("c1", "Sara"), state.dialog)
    }

    @Test
    fun `a pending action on a contact that vanished lapses instead of showing an empty dialog`() {
        val state = buildContactsState(
            rows = emptyList(),
            requests = emptyList(),
            control = ContactsControl(pending = PendingContactAction.Delete("gone")),
        )
        assertEquals(ContactDialog.None, state.dialog)
    }

    @Test
    fun `blocking a contact hides it from the list, so its sheet closes with it`() {
        val state = buildContactsState(
            rows = emptyList(),
            requests = emptyList(),
            control = ContactsControl(sheetContactId = "c1"),
        )
        assertNull(state.sheetFor)
    }

    @Test
    fun `rejecting a request resolves to a dialog naming the requester`() {
        val request = ContactRequestRow(
            requestId = "r1",
            name = "Ali",
            userHash = "vm2-AAAA",
            identityHash = byteArrayOf(2),
        )
        val state = buildContactsState(
            rows = emptyList(),
            requests = listOf(request),
            control = ContactsControl(pending = PendingContactAction.RejectRequest("r1")),
        )
        assertEquals(ContactDialog.RejectRequest("r1", "Ali"), state.dialog)
    }

    @Test
    fun `contacts are ordered by name`() {
        val ordered = listOf(row(id = "b", name = "بهرام"), row(id = "a", name = "آرش"))
            .sortedByPersianName()
            .map { it.id }
        assertEquals(listOf("a", "b"), ordered)
    }

    private fun row(
        id: String = "c1",
        name: String = "Sara",
        userHash: String = "vm2-ABCD",
    ) = ContactRow(
        id = id,
        name = name,
        userHash = userHash,
        identityHash = byteArrayOf(1),
        status = ContactRelationshipStatus.APPROVED,
        blocked = false,
        keyChangePending = false,
        verified = false,
        sharesLocation = false,
        distanceMeters = null,
    )
}
