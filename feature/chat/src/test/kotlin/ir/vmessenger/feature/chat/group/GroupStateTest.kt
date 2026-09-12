package ir.vmessenger.feature.chat.group

import ir.vmessenger.feature.chat.IdentitySeed
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStateTest {

    @Test
    fun `ticking a contact on adds it to the selection`() {
        val selected = persistentSetOf<String>().toggleWithin(GroupLimits.MAX_OTHER_MEMBERS, "c1")
        assertEquals(setOf("c1"), selected)
    }

    @Test
    fun `ticking a contact off works even at the cap`() {
        val full = (1..GroupLimits.MAX_OTHER_MEMBERS).map { "c$it" }.toPersistentSet()
        assertEquals(GroupLimits.MAX_OTHER_MEMBERS - 1, full.toggleWithin(GroupLimits.MAX_OTHER_MEMBERS, "c1").size)
    }

    @Test
    fun `the thirty-second other member is refused rather than trimmed later`() {
        val full = (1..GroupLimits.MAX_OTHER_MEMBERS).map { "c$it" }.toPersistentSet()
        val after = full.toggleWithin(GroupLimits.MAX_OTHER_MEMBERS, "one-too-many")
        assertEquals(GroupLimits.MAX_OTHER_MEMBERS, after.size)
        assertFalse("one-too-many" in after)
    }

    @Test
    fun `a full picker offers no new ticks but keeps the ones it has`() {
        val state = picker(selected = (1..GroupLimits.MAX_OTHER_MEMBERS).map { "c$it" })
        assertTrue(state.atCapacity)
        assertTrue(state.canSelect("c1"))
        assertFalse(state.canSelect("c99"))
    }

    @Test
    fun `an add-members picker is capped by the seats the group has left`() {
        val state = picker(selected = listOf("c1"), capacity = 2)
        assertFalse(state.atCapacity)
        assertTrue(state.canSelect("c2"))
        assertEquals(1, state.selectedCount)
    }

    @Test
    fun `search that matches nothing is not the same as having nobody to pick`() {
        val state = picker(contacts = listOf(contact("c1", "سارا")), query = "zzz").copy(loading = false)
        assertTrue(state.isNoResults)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `search matches the user hash typed without its dashes`() {
        val state = picker(contacts = listOf(contact("c1", "سارا", "vm2-ABCD-EFGH")), query = "abcdefgh")
        assertEquals(listOf("c1"), state.visible.map { it.contactId })
    }

    @Test
    fun `a blank name is not a name`() {
        assertFalse(GroupLimits.isValidName(""))
        assertFalse(GroupLimits.isValidName("   "))
        assertTrue(GroupLimits.isValidName("  تیم  "))
    }

    @Test
    fun `a name is measured after trimming and capped at sixty-four`() {
        assertTrue(GroupLimits.isValidName("x".repeat(GroupLimits.MAX_NAME_LENGTH)))
        assertFalse(GroupLimits.isValidName("x".repeat(GroupLimits.MAX_NAME_LENGTH + 1)))
        assertTrue(GroupLimits.isValidName(" ${"x".repeat(GroupLimits.MAX_NAME_LENGTH)} "))
    }

    @Test
    fun `create waits for both a member and a name`() {
        val empty = NewGroupUiState()
        assertFalse(empty.canContinue)
        assertFalse(empty.canCreate)

        val picked = NewGroupUiState(picker = picker(selected = listOf("c1")))
        assertTrue(picked.canContinue)
        assertFalse(picked.canCreate)

        assertTrue(picked.copy(name = "تیم").canCreate)
    }

    @Test
    fun `a create already in flight cannot be started twice`() {
        val state = NewGroupUiState(picker = picker(selected = listOf("c1")), name = "تیم", creating = true)
        assertFalse(state.canCreate)
        assertFalse(state.canContinue)
    }

    @Test
    fun `the creator manages the group and closes it instead of leaving`() {
        val state = groupInfo(createdByMe = true)
        assertTrue(state.canManage)
        assertTrue(state.canAddMembers)
        assertTrue(state.canClose)
        assertFalse(state.canLeave)
    }

    @Test
    fun `an ordinary member can only leave`() {
        val state = groupInfo(createdByMe = false)
        assertFalse(state.canManage)
        assertFalse(state.canAddMembers)
        assertFalse(state.canClose)
        assertTrue(state.canLeave)
    }

    @Test
    fun `a closed group offers no action at all`() {
        assertFalse(groupInfo(createdByMe = true, closed = true).canManage)
        assertFalse(groupInfo(createdByMe = true, closed = true).canClose)
        assertFalse(groupInfo(createdByMe = false, closed = true).canLeave)
    }

    @Test
    fun `a full group stops offering more members`() {
        val state = groupInfo(
            createdByMe = true,
            members = (1..GroupLimits.MAX_MEMBERS).map { member("h$it") },
        )
        assertFalse(state.canAddMembers)
        assertEquals(0, state.remainingSeats)
    }

    @Test
    fun `the screen pops only once the database has answered`() {
        assertFalse(GroupInfoUiState().notFound)
        assertTrue(GroupInfoUiState(loading = false).notFound)
    }

    @Test
    fun `a member who is not a contact is shown as unknown`() {
        assertEquals("ناشناس", member("h1", name = "Mallory").label("ناشناس"))
        assertEquals("سارا", member("h1", name = "سارا", contactId = "c1").label("ناشناس"))
        // The user's own row is trustworthy without being a contact of themselves.
        assertEquals("من", member("h1", name = "من", isMe = true).label("ناشناس"))
    }

    @Test
    fun `only a stranger who has not been asked yet offers the add-contact action`() {
        assertTrue(member("h1").canBeAddedAsContact)
        assertFalse(member("h1", contactId = "c1").canBeAddedAsContact)
        assertFalse(member("h1", isMe = true).canBeAddedAsContact)
        assertFalse(member("h1", requestPending = true).canBeAddedAsContact)
    }

    @Test
    fun `an identity hash survives the round trip through bytes`() {
        val hex = "0a1bff00"
        assertEquals(hex, hexToBytes(hex).toHex())
        // Odd or malformed input seeds nothing instead of throwing while drawing.
        assertEquals(0, hexToBytes("abc").size)
    }

    private fun picker(
        contacts: List<GroupPickerContact> = emptyList(),
        selected: List<String> = emptyList(),
        query: String = "",
        capacity: Int = GroupLimits.MAX_OTHER_MEMBERS,
    ) = GroupPickerState(
        contacts = contacts.toImmutableList(),
        selected = selected.toPersistentSet(),
        query = query,
        capacity = capacity,
        loading = false,
    )

    private fun contact(id: String, name: String, userHash: String = "vm2-AAAA") = GroupPickerContact(
        contactId = id,
        name = name,
        userHash = userHash,
        seed = IdentitySeed(byteArrayOf(1)),
    )

    private fun groupInfo(
        createdByMe: Boolean,
        closed: Boolean = false,
        members: List<GroupMemberRow> = listOf(member("h1"), member("h2")),
    ) = GroupInfoUiState(
        loading = false,
        exists = true,
        name = "تیم",
        createdByMe = createdByMe,
        closed = closed,
        members = members.toImmutableList(),
    )

    private fun member(
        identityHash: String,
        name: String = "",
        contactId: String? = null,
        isMe: Boolean = false,
        requestPending: Boolean = false,
    ) = GroupMemberRow(
        identityHash = identityHash,
        name = name,
        seed = IdentitySeed(byteArrayOf(1)),
        contactId = contactId,
        isCreator = false,
        isMe = isMe,
        requestPending = requestPending,
    )
}
