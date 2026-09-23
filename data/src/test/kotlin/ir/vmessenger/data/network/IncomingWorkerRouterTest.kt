package ir.vmessenger.data.network

import ir.vmessenger.network.messaging.IncomingEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class IncomingWorkerRouterTest {
    private fun envelope(contactId: String, id: String = "m-$contactId") =
        IncomingEnvelope(envelope = InboundFixtures.chatEnvelope(id), contactId = contactId, session = null)

    @Test
    fun slowContactDoesNotStallOthers() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val handled = Channel<String>(Channel.UNLIMITED)
        val router = IncomingWorkerRouter(Dispatchers.Default, handler = { incoming ->
            if (incoming.contactId == "slow") release.await()
            handled.send(incoming.contactId)
        })
        try {
            router.route(envelope("slow"))
            router.route(envelope("fast"))

            assertEquals("fast", withTimeout(2_000) { handled.receive() })
            release.complete(Unit)
            assertEquals("slow", withTimeout(2_000) { handled.receive() })
        } finally {
            router.stop()
        }
    }

    @Test
    fun handlerFailureDoesNotKillWorker() = runBlocking {
        val handled = Channel<String>(Channel.UNLIMITED)
        val router = IncomingWorkerRouter(Dispatchers.Default, handler = { incoming ->
            if (incoming.envelope.messageId.toStringUtf8() == "boom") error("handler exploded")
            handled.send(incoming.envelope.messageId.toStringUtf8())
        })
        try {
            router.route(envelope("a", id = "boom"))
            router.route(envelope("a", id = "after"))

            assertEquals("after", withTimeout(2_000) { handled.receive() })
        } finally {
            router.stop()
        }
    }

    @Test
    fun deadWorkerIsReplacedAndLeftoversReRouted() = runBlocking {
        val handled = Channel<String>(Channel.UNLIMITED)
        val gate = CompletableDeferred<Unit>()
        val router = IncomingWorkerRouter(Dispatchers.Default, handler = { incoming ->
            val id = incoming.envelope.messageId.toStringUtf8()
            if (id == "fatal") {
                gate.await()
                throw NotImplementedError("Error escaping the handler")
            }
            handled.send(id)
        })
        try {
            // Queue two envelopes behind the fatal one while the worker is blocked on it.
            router.route(envelope("a", id = "fatal"))
            router.route(envelope("a", id = "second"))
            router.route(envelope("a", id = "third"))
            gate.complete(Unit)

            assertEquals("second", withTimeout(2_000) { handled.receive() })
            assertEquals("third", withTimeout(2_000) { handled.receive() })
            // The replacement worker keeps serving the contact.
            router.route(envelope("a", id = "fourth"))
            assertEquals("fourth", withTimeout(2_000) { handled.receive() })
            assertEquals(1, router.workerCount())
        } finally {
            router.stop()
        }
    }

    @Test
    fun handlerTimeoutDoesNotKillWorker() = runBlocking {
        val handled = Channel<String>(Channel.UNLIMITED)
        val router = IncomingWorkerRouter(Dispatchers.Default, handler = { incoming ->
            val id = incoming.envelope.messageId.toStringUtf8()
            if (id == "slow") withTimeout(10) { delay(1_000) }
            handled.send(id)
        })
        try {
            router.route(envelope("a", id = "slow"))
            router.route(envelope("a", id = "after"))

            assertEquals("after", withTimeout(2_000) { handled.receive() })
            assertEquals(1, router.workerCount())
        } finally {
            router.stop()
        }
    }

    @Test
    fun strangersShareOneWorker() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val router = IncomingWorkerRouter(Dispatchers.Default, handler = { release.await() })
        try {
            router.route(envelope("stranger:aa"))
            router.route(envelope("stranger:bb"))
            router.route(envelope("contact"))

            assertEquals(2, router.workerCount())
        } finally {
            release.complete(Unit)
            router.stop()
        }
    }

    @Test
    fun idleWorkersRetireAndComeBack() = runBlocking {
        val handled = Channel<String>(Channel.UNLIMITED)
        val calls = AtomicInteger()
        val secondMayFinish = CompletableDeferred<Unit>()
        val router = IncomingWorkerRouter(
            Dispatchers.Default,
            handler = {
                // The second envelope is held, so its worker is certainly alive when counted: read
                // after handling, a loaded machine could let 50 ms pass and retire it again first.
                if (calls.incrementAndGet() == 2) secondMayFinish.await()
                handled.send(it.contactId)
            },
            idleTimeoutMs = 50,
        )
        try {
            router.route(envelope("a"))
            assertEquals("a", withTimeout(2_000) { handled.receive() })
            withTimeout(2_000) {
                while (router.workerCount() != 0) delay(10)
            }

            router.route(envelope("a"))
            assertEquals(1, router.workerCount())
            secondMayFinish.complete(Unit)
            assertEquals("a", withTimeout(2_000) { handled.receive() })
        } finally {
            secondMayFinish.complete(Unit)
            router.stop()
        }
    }
}
