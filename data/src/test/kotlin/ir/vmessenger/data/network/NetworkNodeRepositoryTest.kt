package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.core.common.network.NodeRanking
import ir.vmessenger.core.common.network.NodeTrust
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.NodeRole
import ir.vmessenger.data.activity.testActivityLogger
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.repository.NodeAddMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkNodeRepositoryTest {
    private val bootstrapDao = FakeBootstrapNodeDao()
    private val relayDao = FakeRelayNodeDao()

    private fun repository(allowInsecureLocal: Boolean = false) =
        NetworkNodeRepository(bootstrapDao, relayDao, testActivityLogger()) {
            NodeAddressPolicy(allowInsecureLocal)
        }

    private fun SignedNodeRecordSigner.signRelay(address: String, key: KeyPair, expires: Long) = sign(
        address = address,
        role = NodeRole.NODE_ROLE_RELAY,
        publicKey = key.publicKey,
        capabilities = listOf("relay"),
        expiresAtUnixMs = expires,
        ed25519PrivateKey = key.privateKey,
    )

    @Test
    fun exchangedNodesImportedDisabled() = runTest {
        val repo = repository()
        repo.seedDefaults()
        val peer = ByteArray(32) { 9 }
        repo.importExchangedNodes(
            bootstrapAddresses = listOf("wss://evil.example/dht"),
            relayAddresses = listOf("wss://evil.example/relay", "ws://evil.example/relay"),
            learnedFromHash = peer,
        )
        val relay = relayDao.getByAddress("wss://evil.example/relay")!!
        assertFalse(relay.enabled)
        assertEquals(NodeTrust.COMMUNITY.name, relay.trust)
        assertEquals(NodeRanking.PRIORITY_COMMUNITY, relay.priority)
        assertTrue(peer.contentEquals(relay.learnedFromHash))
        assertNull(relayDao.getByAddress("ws://evil.example/relay"))
        val bootstrap = bootstrapDao.getByAddress("wss://evil.example/dht")!!
        assertFalse(bootstrap.enabled)
        assertEquals(NodeTrust.COMMUNITY.name, bootstrap.trust)
        // The active relay stays the built-in one.
        assertEquals(listOf(NetworkConfig.DEFAULT_RELAY_URL), repo.enabledRelayUrls())
        val shown = repo.observeNodes().first().first { it.address == "wss://evil.example/relay" }
        assertTrue(shown.community)
        assertFalse(shown.enabled)
    }

    @Test
    fun learnedDhtNodesImportedDisabledAndPeerCannotReenableUserChoice() = runTest {
        val repo = repository()
        repo.seedDefaults()
        repo.importLearnedBootstrapAddresses(setOf("wss://dht.example/dht"), NetworkNodeRepository.SOURCE_CACHED_DHT)
        assertFalse(bootstrapDao.getByAddress("wss://dht.example/dht")!!.enabled)
        // User disables the built-in relay; a later exchange must not flip it back or change its trust.
        repo.setRelayEnabled(NetworkConfig.DEFAULT_RELAY_URL, enabled = false)
        repo.importExchangedNodes(emptyList(), listOf(NetworkConfig.DEFAULT_RELAY_URL))
        val builtIn = relayDao.getByAddress(NetworkConfig.DEFAULT_RELAY_URL)!!
        assertFalse(builtIn.enabled)
        assertEquals(NodeTrust.BUILT_IN.name, builtIn.trust)
        assertEquals(NodeRanking.PRIORITY_BUILT_IN, builtIn.priority)
    }

    @Test
    fun officialRecordEnabled() = runTest {
        val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        val operator = crypto.generateEd25519KeyPair()
        val other = crypto.generateEd25519KeyPair()
        val verifier = SignedNodeRecordVerifier(crypto, operator.publicKey)
        val signer = SignedNodeRecordSigner(crypto, verifier)
        val expires = System.currentTimeMillis() + 60_000
        val official = signer.signRelay("wss://relay2.vmessenger.ir/relay", operator, expires)
        val community = signer.signRelay("wss://community.example/relay", other, expires)
        val insecureOfficial = signer.signRelay("ws://relay3.vmessenger.ir/relay", operator, expires)
        val repo = repository()
        repo.seedDefaults()
        repo.importSignedNodeRecords(listOf(official, community, insecureOfficial), verifier)

        val officialRow = relayDao.getByAddress("wss://relay2.vmessenger.ir/relay")!!
        assertTrue(officialRow.enabled)
        assertEquals(NodeTrust.OFFICIAL.name, officialRow.trust)
        assertEquals(NodeRanking.PRIORITY_OFFICIAL, officialRow.priority)
        val communityRow = relayDao.getByAddress("wss://community.example/relay")!!
        assertFalse(communityRow.enabled)
        assertEquals(NodeTrust.COMMUNITY.name, communityRow.trust)
        // Even an operator signature does not bypass the address policy.
        assertNull(relayDao.getByAddress("ws://relay3.vmessenger.ir/relay"))
        assertEquals(
            setOf(NetworkConfig.DEFAULT_RELAY_URL, "wss://relay2.vmessenger.ir/relay"),
            repo.enabledRelayUrls().toSet(),
        )
    }

    @Test
    fun officialRecordPromotesExistingCommunityRow() = runTest {
        val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        val operator = crypto.generateEd25519KeyPair()
        val verifier = SignedNodeRecordVerifier(crypto, operator.publicKey)
        val signer = SignedNodeRecordSigner(crypto, verifier)
        val repo = repository()
        repo.importExchangedNodes(emptyList(), listOf("wss://relay2.vmessenger.ir/relay"))
        repo.recordRelayResult("wss://relay2.vmessenger.ir/relay", ok = false)
        val expires = System.currentTimeMillis() + 60_000
        val record = signer.signRelay("wss://relay2.vmessenger.ir/relay", operator, expires)
        repo.importSignedNodeRecords(listOf(record), verifier)
        val row = relayDao.getByAddress("wss://relay2.vmessenger.ir/relay")!!
        assertTrue(row.enabled)
        assertEquals(NodeTrust.OFFICIAL.name, row.trust)
        assertEquals(1, row.failCount)
    }

    @Test
    fun userAddPromotesCommunityRow() = runTest {
        val repo = repository()
        repo.seedDefaults()
        repo.importExchangedNodes(
            bootstrapAddresses = listOf("wss://friend.example/dht"),
            relayAddresses = listOf("wss://friend.example/relay"),
            learnedFromHash = ByteArray(32) { 7 },
        )
        repo.recordRelayResult("wss://friend.example/relay", ok = false)
        assertFalse(relayDao.getByAddress("wss://friend.example/relay")!!.enabled)

        val added = repo.addNode("wss://friend.example/relay", NetworkNodeRole.RELAY)
        assertTrue(added is AppResult.Success)
        val relay = relayDao.getByAddress("wss://friend.example/relay")!!
        assertTrue(relay.enabled)
        assertEquals(NodeTrust.USER.name, relay.trust)
        assertEquals(NodeRanking.PRIORITY_USER, relay.priority)
        assertEquals(NetworkNodeRepository.SOURCE_USER, relay.source)
        // Health history survives the upgrade.
        assertEquals(1, relay.failCount)
        // A user relay now outranks the built-in one.
        assertEquals(listOf("wss://friend.example/relay", NetworkConfig.DEFAULT_RELAY_URL), repo.enabledRelayUrls())

        assertTrue(repo.addNode("wss://friend.example/dht", NetworkNodeRole.BOOTSTRAP) is AppResult.Success)
        val bootstrap = bootstrapDao.getByAddress("wss://friend.example/dht")!!
        assertTrue(bootstrap.enabled)
        assertEquals(NodeTrust.USER.name, bootstrap.trust)
        assertEquals(NodeRanking.PRIORITY_USER, bootstrap.priority)
    }

    @Test
    fun userAddNeverDowngradesBuiltInOrOfficialRow() = runTest {
        val repo = repository()
        repo.seedDefaults()
        repo.setRelayEnabled(NetworkConfig.DEFAULT_RELAY_URL, enabled = false)
        assertTrue(repo.addNode(NetworkConfig.DEFAULT_RELAY_URL, NetworkNodeRole.RELAY) is AppResult.Success)
        val builtIn = relayDao.getByAddress(NetworkConfig.DEFAULT_RELAY_URL)!!
        assertEquals(NodeTrust.BUILT_IN.name, builtIn.trust)
        assertEquals(NodeRanking.PRIORITY_BUILT_IN, builtIn.priority)
        assertFalse(builtIn.enabled)
    }

    @Test
    fun communityRowsCappedPerTable() = runTest {
        val repo = repository()
        repo.seedDefaults()
        val peer = ByteArray(32) { 3 }
        // 20 per envelope; many envelopes from one (or many) peers must still hit the global cap.
        repeat(5) { batch ->
            repo.importExchangedNodes(
                bootstrapAddresses = (1..20).map { "wss://dht-$batch-$it.example/dht" },
                relayAddresses = (1..20).map { "wss://relay-$batch-$it.example/relay" },
                learnedFromHash = peer,
            )
        }
        val communityRelays = relayDao.getAll().count { it.trust == NodeTrust.COMMUNITY.name }
        val communityBootstrap = bootstrapDao.getAll().count { it.trust == NodeTrust.COMMUNITY.name }
        assertEquals(NetworkNodeRepository.MAX_COMMUNITY_ROWS, communityRelays)
        assertEquals(NetworkNodeRepository.MAX_COMMUNITY_ROWS, communityBootstrap)
        // The cap only limits hints: an explicit user add still goes through.
        assertTrue(repo.addNode("wss://mine.example/relay", NetworkNodeRole.RELAY) is AppResult.Success)
        assertEquals(NodeTrust.USER.name, relayDao.getByAddress("wss://mine.example/relay")!!.trust)
    }

    @Test
    fun wsRejectedInRelease() = runTest {
        val repo = repository(allowInsecureLocal = false)
        val relay = repo.addNode("ws://10.0.2.2:8443/relay", NetworkNodeRole.RELAY)
        assertTrue(relay is AppResult.Error)
        val bootstrap = repo.addNode("10.0.2.2:46555", NetworkNodeRole.BOOTSTRAP)
        assertTrue(bootstrap is AppResult.Error)
        val plain = repo.addNode("relay.example", NetworkNodeRole.RELAY)
        assertTrue(plain is AppResult.Error)
        assertTrue(relayDao.getAll().isEmpty())
        assertTrue(bootstrapDao.getAll().isEmpty())

        val secure = repo.addNode("wss://my.example/relay", NetworkNodeRole.RELAY)
        assertTrue(secure is AppResult.Success)
        val row = relayDao.getByAddress("wss://my.example/relay")!!
        assertTrue(row.enabled)
        assertEquals(NodeTrust.USER.name, row.trust)
        assertEquals(NodeRanking.PRIORITY_USER, row.priority)
        assertEquals(NodeTrust.USER, (secure as AppResult.Success).data.trust)
    }

    @Test
    fun wsLocalAllowedInDebug() = runTest {
        val repo = repository(allowInsecureLocal = true)
        assertTrue(repo.addNode("ws://10.0.2.2:8443/relay", NetworkNodeRole.RELAY) is AppResult.Success)
        assertTrue(repo.addNode("10.0.2.2:46555", NetworkNodeRole.BOOTSTRAP) is AppResult.Success)
        assertTrue(repo.addNode("ws://192.168.1.5:46555", NetworkNodeRole.BOOTSTRAP) is AppResult.Success)
        // Still not for public hosts.
        assertTrue(repo.addNode("ws://relay.example/relay", NetworkNodeRole.RELAY) is AppResult.Error)
        assertTrue(repo.addNode("relay.example:46555", NetworkNodeRole.BOOTSTRAP) is AppResult.Error)
        assertEquals(listOf("ws://10.0.2.2:8443/relay"), repo.enabledRelayUrls())
        assertEquals(
            setOf("10.0.2.2:46555", "ws://192.168.1.5:46555"),
            repo.enabledBootstrapNodes().map { it.address }.toSet(),
        )
    }

    @Test
    fun enabledRelayUrlsAreRankedNotDaoOrdered() = runTest {
        val repo = repository()
        repo.addRelayNode("wss://user.example/relay")
        repo.seedDefaults()
        // DAO order is user, built-in; ranking must put the user relay (150) first regardless.
        assertEquals(listOf("wss://user.example/relay", NetworkConfig.DEFAULT_RELAY_URL), repo.enabledRelayUrls())
        repeat(3) { repo.recordRelayResult("wss://user.example/relay", ok = false) }
        assertEquals(listOf(NetworkConfig.DEFAULT_RELAY_URL, "wss://user.example/relay"), repo.enabledRelayUrls())
        repo.recordRelayResult("wss://user.example/relay", ok = true)
        assertEquals(listOf("wss://user.example/relay", NetworkConfig.DEFAULT_RELAY_URL), repo.enabledRelayUrls())
    }

    @Test
    fun aUserAddWithANewPinReplacesTheLocationsRow() = runTest {
        val repo = repository()
        repo.addNode("wss://203.0.113.10/relay#pin-sha256=$PIN_A", NetworkNodeRole.RELAY)
        repo.recordRelayResult("wss://203.0.113.10/relay#pin-sha256=$PIN_A", ok = false)
        repo.addNode("WSS://203.0.113.10:443/relay#pin-sha256=$PIN_B", NetworkNodeRole.RELAY)
        val relays = relayDao.getAll().filter { "203.0.113.10" in it.address }
        assertEquals(listOf("wss://203.0.113.10/relay#pin-sha256=$PIN_B"), relays.map { it.address })
        assertEquals(0, relays.single().failCount)
    }

    @Test
    fun theNetworkCannotChangeAStoredLocationsPin() = runTest {
        val repo = repository()
        repo.addNode("wss://203.0.113.10/relay#pin-sha256=$PIN_A", NetworkNodeRole.RELAY)
        repo.importExchangedNodes(emptyList(), listOf("wss://203.0.113.10/relay#pin-sha256=$PIN_B"))
        repo.importExchangedNodes(emptyList(), listOf("wss://203.0.113.10/relay"))
        assertEquals(
            listOf("wss://203.0.113.10/relay#pin-sha256=$PIN_A"),
            relayDao.getAll().filter { "203.0.113.10" in it.address }.map { it.address },
        )
    }

    @Test
    fun aRestoredBackupKeepsTheNewerPin() = runTest {
        val repo = repository()
        repo.addNode("wss://203.0.113.10/relay#pin-sha256=$PIN_B", NetworkNodeRole.RELAY)
        repo.addNode("wss://203.0.113.10/relay#pin-sha256=$PIN_A", NetworkNodeRole.RELAY, NodeAddMode.KeepExisting)
        assertEquals(
            listOf("wss://203.0.113.10/relay#pin-sha256=$PIN_B"),
            relayDao.getAll().filter { "203.0.113.10" in it.address }.map { it.address },
        )
    }

    private companion object {
        const val PIN_A = "601FQOh6ckV1-Qbw-9F3cGprfojLs5_j4Hkn7DPKFfc"
        const val PIN_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA"
    }
}
