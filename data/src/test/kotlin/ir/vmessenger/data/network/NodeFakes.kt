package ir.vmessenger.data.network

import ir.vmessenger.core.database.dao.BootstrapNodeDao
import ir.vmessenger.core.database.dao.RelayNodeDao
import ir.vmessenger.core.database.entity.BootstrapNodeEntity
import ir.vmessenger.core.database.entity.RelayNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [RelayNodeDao]; returns rows in insertion order so ranking is exercised by the repository. */
class FakeRelayNodeDao : RelayNodeDao {
    val rows = MutableStateFlow<List<RelayNodeEntity>>(emptyList())

    override suspend fun upsert(entity: RelayNodeEntity) {
        rows.value = rows.value.filterNot { it.address == entity.address } + entity
    }

    override fun observeAll(): Flow<List<RelayNodeEntity>> = rows.map { it.sortedByDescending { r -> r.priority } }

    override suspend fun getEnabled(): List<RelayNodeEntity> = rows.value.filter { it.enabled }

    override suspend fun getAll(): List<RelayNodeEntity> = rows.value

    override suspend fun getByAddress(address: String): RelayNodeEntity? =
        rows.value.firstOrNull { it.address == address }

    override suspend fun markOk(address: String, ts: Long) =
        update(address) { it.copy(lastOkUnixMs = ts, failCount = 0) }

    override suspend fun markFail(address: String, ts: Long) =
        update(address) { it.copy(lastFailUnixMs = ts, failCount = it.failCount + 1) }

    override suspend fun setEnabled(address: String, enabled: Boolean) = update(address) { it.copy(enabled = enabled) }

    override suspend fun deleteByAddress(address: String) {
        rows.value = rows.value.filterNot { it.address == address }
    }

    private fun update(address: String, transform: (RelayNodeEntity) -> RelayNodeEntity) {
        rows.value = rows.value.map { if (it.address == address) transform(it) else it }
    }
}

/** In-memory [BootstrapNodeDao]; returns rows in insertion order. */
class FakeBootstrapNodeDao : BootstrapNodeDao {
    val rows = MutableStateFlow<List<BootstrapNodeEntity>>(emptyList())

    override suspend fun upsert(entity: BootstrapNodeEntity) {
        rows.value = rows.value.filterNot { it.address == entity.address } + entity
    }

    override fun observeEnabled(): Flow<List<BootstrapNodeEntity>> = rows.map { list -> list.filter { it.enabled } }

    override fun observeAll(): Flow<List<BootstrapNodeEntity>> = rows.map { it.sortedByDescending { r -> r.priority } }

    override suspend fun getEnabled(): List<BootstrapNodeEntity> = rows.value.filter { it.enabled }

    override suspend fun getAll(): List<BootstrapNodeEntity> = rows.value

    override suspend fun getByAddress(address: String): BootstrapNodeEntity? =
        rows.value.firstOrNull { it.address == address }

    override suspend fun markOk(address: String, ts: Long) =
        update(address) { it.copy(lastOkUnixMs = ts, failCount = 0) }

    override suspend fun markFail(address: String, ts: Long) =
        update(address) { it.copy(lastFailUnixMs = ts, failCount = it.failCount + 1) }

    override suspend fun setEnabled(address: String, enabled: Boolean) = update(address) { it.copy(enabled = enabled) }

    override suspend fun deleteByAddress(address: String) {
        rows.value = rows.value.filterNot { it.address == address }
    }

    private fun update(address: String, transform: (BootstrapNodeEntity) -> BootstrapNodeEntity) {
        rows.value = rows.value.map { if (it.address == address) transform(it) else it }
    }
}
