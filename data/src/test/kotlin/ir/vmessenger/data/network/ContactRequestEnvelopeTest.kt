package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.proto.app.v1.ContactRequest
import ir.vmessenger.core.proto.app.v1.ContactResponse
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRequestEnvelopeTest {
    @Test
    fun contactRequestEnvelopeRoundTripsFields() {
        val requestId = "test-request-id"
        val identityPub = ByteArray(32) { it.toByte() }
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("contact-req-$requestId"))
            .setContactRequest(
                ContactRequest.newBuilder()
                    .setRequesterIdentityPub(ByteString.copyFrom(identityPub))
                    .setRequesterUserHash("ABCD-EFGH")
                    .setRequesterDisplayName("Ali")
                    .setRequestId(ByteString.copyFromUtf8(requestId)),
            )
            .build()

        assertTrue(envelope.hasContactRequest())
        val request = envelope.contactRequest
        assertEquals("Ali", request.requesterDisplayName)
        assertEquals("ABCD-EFGH", request.requesterUserHash)
        assertEquals(requestId, request.requestId.toStringUtf8())
        assertTrue(request.requesterIdentityPub.toByteArray().contentEquals(identityPub))
    }

    @Test
    fun contactResponseEnvelopePreservesAcceptType() {
        val envelope = MessageEnvelope.newBuilder()
            .setContactResponse(
                ContactResponse.newBuilder()
                    .setRequestId(ByteString.copyFromUtf8("req-1"))
                    .setType(ContactResponseType.CONTACT_RESPONSE_ACCEPT),
            )
            .build()

        assertEquals(ContactResponseType.CONTACT_RESPONSE_ACCEPT, envelope.contactResponse.type)
    }
}
