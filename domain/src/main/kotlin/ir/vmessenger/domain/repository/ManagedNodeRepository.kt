package ir.vmessenger.domain.repository

import ir.vmessenger.domain.model.ManagedNode
import kotlinx.coroutines.flow.Flow

/** The servers this device set up as nodes. */
interface ManagedNodeRepository {
    fun observe(): Flow<List<ManagedNode>>

    suspend fun get(id: String): ManagedNode?

    suspend fun getByHost(host: String, sshPort: Int): ManagedNode?

    /** Stores [node]; a new READY node is logged as set up, a changed one as updated. */
    suspend fun save(node: ManagedNode)

    suspend fun forget(id: String)

    suspend fun markChecked(id: String, ok: Boolean)
}
