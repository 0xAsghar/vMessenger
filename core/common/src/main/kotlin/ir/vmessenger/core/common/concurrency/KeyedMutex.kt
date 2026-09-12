package ir.vmessenger.core.common.concurrency

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One entry of a [KeyedMutex]: a mutex plus a reference count. Subclass it to
 * hang per-key state off the slot (for example an open session) that must live
 * exactly as long as somebody holds a reference.
 */
open class KeyedSlot {
    val mutex = Mutex()
    internal val refs = AtomicInteger()

    /** Number of live references (lock holders + explicit [KeyedMutex.retain]s). */
    val refCount: Int
        get() = refs.get()
}

/**
 * Per-key mutual exclusion without a global lock: work under key X never waits
 * for key Y. Slots are reference counted — acquiring (via [withLock] or
 * [retain]) increments the count, releasing decrements it and removes the entry
 * at zero. Both transitions run inside the map's own atomic `compute` calls, so
 * a slot can never be removed while a concurrent acquirer has just found it.
 */
class KeyedMutex<K : Any, S : KeyedSlot>(private val newSlot: () -> S) {
    private val slots = ConcurrentHashMap<K, S>()

    /** Number of live slots; zero once every holder has released. */
    val size: Int
        get() = slots.size

    /** The slot for [key] if anybody currently holds it, without taking a reference. */
    fun peek(key: K): S? = slots[key]

    /** Snapshot of the keys with a live slot. */
    fun keys(): Set<K> = slots.keys.toSet()

    /** Runs [block] under the mutex of [key]; the slot is retained for the duration. */
    suspend fun <T> withLock(key: K, block: suspend (S) -> T): T {
        val slot = retain(key)
        try {
            return slot.mutex.withLock { block(slot) }
        } finally {
            release(key, slot)
        }
    }

    /**
     * Takes a reference on the slot for [key] (creating it when absent) without
     * locking it. Every [retain] must be paired with exactly one [release].
     */
    fun retain(key: K): S =
        checkNotNull(
            slots.compute(key) { _, existing ->
                (existing ?: newSlot()).also { it.refs.incrementAndGet() }
            },
        )

    /** Drops one reference on [slot]; the entry disappears when the count hits zero. */
    fun release(key: K, slot: S) {
        slots.computeIfPresent(key) { _, current ->
            if (current === slot && current.refs.decrementAndGet() == 0) null else current
        }
    }
}

/** A [KeyedMutex] whose slots carry no extra state. */
fun <K : Any> KeyedMutex(): KeyedMutex<K, KeyedSlot> = KeyedMutex { KeyedSlot() }
