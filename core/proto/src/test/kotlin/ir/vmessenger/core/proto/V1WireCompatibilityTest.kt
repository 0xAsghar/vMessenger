package ir.vmessenger.core.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.0 talks to phones still running 1.1.2, and what makes that work is that every change to the
 * schema since was an addition. This holds the line: the schema 1.1.2 shipped (copied here from
 * the `v1.1.2` tag) against the one this build compiles.
 *
 * Protobuf identifies a field by its number alone. So a field 1.1.2 knows must keep its number, its
 * type and its cardinality, and stay in or out of the oneof it was in; a number may only disappear
 * if it is reserved, so it can never be handed to something else; and an enum value 1.1.2 can send
 * must still be defined. Names may change — they never reach the wire.
 */
class V1WireCompatibilityTest {
    private val released = ProtoText.parse(FILES.map { resource("v1.1.2/$it") })
    private val current = ProtoText.parse(FILES.map { File("src/main/proto/$it").readText() })

    @Test
    fun `the schema 1_1_2 shipped is the one this test holds`() {
        // A guard on the guard: an empty parse would pass everything below.
        assertTrue(released.messages.getValue("vmessenger.app.v1.MessageEnvelope").size > FIELDS_1_1_2_ENVELOPE)
        assertTrue(released.enums.getValue("vmessenger.app.v1.AttachmentKind").isNotEmpty())
    }

    @Test
    fun `every message 1_1_2 knows is still here`() {
        val missing = released.messages.keys - current.messages.keys
        assertTrue("messages 1.1.2 can send or expect are gone: $missing", missing.isEmpty())
    }

    @Test
    fun `every field 1_1_2 knows keeps its number, its type, its cardinality and its oneof`() {
        val broken = released.messages.flatMap { (message, fields) ->
            val now = current.messages[message].orEmpty()
            fields.mapNotNull { (number, field) ->
                val kept = now[number]
                when {
                    kept == null && number in current.reserved[message].orEmpty() -> null
                    kept == null -> "$message.${field.name} = $number was removed without being reserved"
                    kept.type != field.type ->
                        "$message.${field.name} = $number changed type ${field.type} -> ${kept.type}"
                    kept.repeated != field.repeated -> "$message.${field.name} = $number changed cardinality"
                    kept.oneof != field.oneof -> "$message.${field.name} = $number moved from oneof ${field.oneof}"
                    else -> null
                }
            }
        }
        assertEquals(emptyList<String>(), broken)
    }

    @Test
    fun `no number 1_1_2 reserved has been reused`() {
        val reused = released.reserved.flatMap { (message, numbers) ->
            numbers.filter { it in current.messages[message].orEmpty() }.map { "$message = $it" }
        }
        assertEquals(emptyList<String>(), reused)
    }

    @Test
    fun `every enum value 1_1_2 can send is still defined`() {
        val lost = released.enums.flatMap { (enum, values) ->
            val now = current.enums[enum].orEmpty()
            values.keys.filter { it !in now }.map { "$enum = $it (${values[it]})" }
        }
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `the 1_1_2 schema compiled for the round-trip test is the released one, renamed`() {
        // V1WireRoundTripTest builds real 1.1.2 messages from a copy under another package; this
        // keeps that copy honest.
        val renamed = File("src/test/proto/v112/messaging.proto").readText()
            .replace("package v112.vmessenger.app.v1;", "package vmessenger.app.v1;")
            .replace("\"ir.vmessenger.core.proto.v112.app.v1\"", "\"ir.vmessenger.core.proto.app.v1\"")
        assertEquals(resource("v1.1.2/vmessenger/app/v1/messaging.proto"), renamed)
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "missing $path" }.readText()

    private companion object {
        /** Everything 1.1.2 compiled. */
        val FILES = listOf(
            "vmessenger/app/v1/messaging.proto",
            "vmessenger/backup/v1/backup.proto",
            "vmessenger/dht/v1/dht.proto",
            "vmessenger/relay/v1/relay.proto",
            "vmessenger/wire/v1/frame.proto",
            "vmessenger/wire/v1/handshake.proto",
            "vmessenger/wire/v1/pairing.proto",
        )

        /** Fewer than the envelope really had; enough to prove the parse found it. */
        const val FIELDS_1_1_2_ENVELOPE = 20
    }
}
