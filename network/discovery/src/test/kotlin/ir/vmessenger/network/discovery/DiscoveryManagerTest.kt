package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryManagerTest {
    private class ScriptedProvider(
        name: String,
        private val result: AppResult<List<Endpoint>>,
    ) : DiscoveryProvider {
        override val id = DiscoveryProviderId(name)

        override suspend fun announce(
            self: DiscoveryIdentity,
            endpoints: List<Endpoint>,
            ed25519PrivateKey: ByteArray,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun resolve(identityHash: ByteArray): AppResult<List<Endpoint>> = result
    }

    @Test
    fun allErrorsReturnError() = runTest {
        val manager = DiscoveryManager(
            setOf(
                ScriptedProvider("a", AppResult.Error(AppError.Network("first"))),
                ScriptedProvider("b", AppResult.Error(AppError.Network("last"))),
            ),
        )
        val result = manager.resolve(ByteArray(32))
        assertTrue(result is AppResult.Error)
        assertEquals(AppError.Network("last"), (result as AppResult.Error).error)
    }

    @Test
    fun notFoundIsEmptySuccess() = runTest {
        val manager = DiscoveryManager(
            setOf(
                ScriptedProvider("a", AppResult.Error(AppError.Network("down"))),
                ScriptedProvider("b", AppResult.Success(emptyList())),
            ),
        )
        val result = manager.resolve(ByteArray(32))
        assertEquals(AppResult.Success(emptyList<Endpoint>()), result)
    }

    @Test
    fun answersAreMergedAndDeduplicated() = runTest {
        val relay = Endpoint(TransportIds.RELAY, "wss://relay.example/relay")
        val direct = Endpoint(TransportIds.INTERNET, "203.0.113.1:48555")
        val manager = DiscoveryManager(
            setOf(
                ScriptedProvider("a", AppResult.Success(listOf(relay))),
                ScriptedProvider("b", AppResult.Success(listOf(relay, direct))),
            ),
        )
        val result = manager.resolve(ByteArray(32)) as AppResult.Success
        assertEquals(listOf(relay, direct), result.data)
    }

    @Test
    fun noProvidersIsEmptySuccess() = runTest {
        assertEquals(AppResult.Success(emptyList<Endpoint>()), DiscoveryManager(emptySet()).resolve(ByteArray(32)))
    }
}
