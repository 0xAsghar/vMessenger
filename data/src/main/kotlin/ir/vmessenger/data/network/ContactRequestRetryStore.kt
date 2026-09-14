package ir.vmessenger.data.network

import ir.vmessenger.core.datastore.ContactRetryPreferences
import ir.vmessenger.core.datastore.ContactRetryRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where [ContactRequestRetryBudget] survives a restart. The app binds the
 * DataStore-backed one; [Transient] is for tests, which want no disk at all.
 */
interface ContactRequestRetryStore {
    suspend fun load(): Map<String, ContactRequestRetryBudget.State>

    suspend fun save(states: Map<String, ContactRequestRetryBudget.State>)

    /** No disk: the budget lives and dies with the process. */
    object Transient : ContactRequestRetryStore {
        override suspend fun load(): Map<String, ContactRequestRetryBudget.State> = emptyMap()

        override suspend fun save(states: Map<String, ContactRequestRetryBudget.State>) = Unit
    }
}

@Singleton
class DataStoreContactRequestRetryStore @Inject constructor(
    private val preferences: ContactRetryPreferences,
) : ContactRequestRetryStore {
    override suspend fun load(): Map<String, ContactRequestRetryBudget.State> =
        preferences.load().mapValues { (_, record) ->
            ContactRequestRetryBudget.State(record.attempts, record.failures, record.nextAttemptUnixMs)
        }

    override suspend fun save(states: Map<String, ContactRequestRetryBudget.State>) =
        preferences.save(
            states.mapValues { (_, state) ->
                ContactRetryRecord(state.attempts, state.failures, state.nextAttemptUnixMs)
            },
        )
}
