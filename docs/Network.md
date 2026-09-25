# vMessenger - Network Architecture

This document describes the networking architecture: the layered model, the interface contracts for each layer, the transport abstraction and automatic transport selection, the connection lifecycle, and how future transports plug in without changing the rest of the system.

The wire format, handshake, and message semantics are specified in [Protocol.md](Protocol.md). The cryptography is specified in [Security.md](Security.md). Peer location (turning an identity into an address) is specified in [Discovery.md](Discovery.md) and [DHT.md](DHT.md).

---

## 1. Philosophy

Networking is decomposed into independent, replaceable layers. Each layer is defined by an interface and communicates with adjacent layers only through that interface. This is what makes vMessenger able to evolve from a single Internet transport today into Bluetooth, Wi-Fi Direct, and mesh transports later without rewrites.

The two hard rules:

- The Discovery layer is completely independent from the Messaging layer. Messaging asks Discovery for endpoints; it never knows how they were found.
- No plaintext crosses the Transport boundary. Encryption sits between Messaging and Transport, so Transport only ever moves opaque ciphertext frames.

---

## 2. The layered model

```mermaid
flowchart TD
  Identity["Identity - who am I, who is the peer, signing"]
  Discovery["Discovery - identity hash to endpoints"]
  Transport["Transport - raw byte channel to an endpoint"]
  Encryption["Encryption - handshake, ratchet, AEAD framing"]
  Messaging["Messaging - sequencing, acks, retries, queues"]

  Messaging --> Encryption
  Encryption --> Transport
  Messaging --> Discovery
  Discovery --> Identity
  Encryption --> Identity
```

Responsibilities:

- Identity: holds the device keypair; signs records and handshakes; verifies peers. See [Security.md](Security.md).
- Discovery: resolves an identity hash to one or more reachable `Endpoint`s. See [Discovery.md](Discovery.md).
- Transport: opens and maintains a bidirectional byte channel to an `Endpoint`; listens for inbound channels.
- Encryption: negotiates a secure session over a channel and seals/opens frames. See [Protocol.md](Protocol.md) and [Security.md](Security.md).
- Messaging: turns application messages into ordered, acknowledged, retryable exchanges over a session.

---

## 3. Layer contracts

These are illustrative Kotlin interfaces that define the boundaries. Exact signatures are finalized during implementation, but the shapes are stable.

### 3.1 Identity

```kotlin
interface IdentityService {
    val self: Identity                       // public key, identity hash, user hash
    fun sign(data: ByteArray): ByteArray     // Ed25519 signature
    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean
}
```

### 3.2 Discovery

```kotlin
interface DiscoveryProvider {
    val id: DiscoveryProviderId

    // Make ourselves reachable (e.g. publish a signed endpoint record).
    suspend fun announce(self: Identity, endpoints: List<Endpoint>): Result<Unit>

    // Resolve a peer's identity hash to current reachable endpoints.
    suspend fun resolve(identityHash: IdentityHash): Result<List<Endpoint>>
}
```

Multiple `DiscoveryProvider`s can be registered (QR/User Hash exchange feeds the contact's identity; the DHT provider resolves live endpoints). See [Discovery.md](Discovery.md).

### 3.3 Transport

```kotlin
interface Transport {
    val id: TransportId
    val capabilities: TransportCapabilities  // reliability, ordering, MTU, reachability class

    fun canReach(endpoint: Endpoint): Boolean
    suspend fun connect(endpoint: Endpoint): Result<Connection>
    fun listen(): Flow<Connection>           // inbound connections
}

interface Connection {
    val remote: Endpoint
    val state: StateFlow<ConnectionState>
    suspend fun write(frame: ByteArray): Result<Unit>
    fun read(): Flow<ByteArray>              // length-delimited frames
    suspend fun close()
}
```

### 3.4 Encryption

```kotlin
// network/messaging/.../SecureChannelFactory.kt
class SecureChannelFactory {
    suspend fun initiate(connection: Connection, self: PeerIdentity, peer: PeerIdentity): Result<SecureSession>
    suspend fun accept(connection: Connection, self: PeerIdentity, expectedPeer: PeerIdentity): Result<SecureSession>
    // Inbound: the peer is resolved from the keys it actually presented.
    suspend fun acceptResolving(
        connection: Connection,
        self: PeerIdentity,
        resolvePeer: suspend (identityPub: ByteArray, staticPub: ByteArray) -> PeerIdentity?,
    ): Result<SecureSession>
}

interface SecureSession {
    val peer: PeerIdentity
    val ratchetState: RatchetState
    suspend fun seal(plaintext: ByteArray, frameType: FrameType = FRAME_TYPE_SECURE): ByteArray
    suspend fun open(frame: ByteArray, counter: Long, frameType: FrameType = FRAME_TYPE_SECURE): ByteArray?
    suspend fun close()   // zeroizes the ratchet state
}
```

The handshake is the three-step, doubly-signed v2 exchange with three Diffie-Hellman operations; `counter` is the ratchet counter carried in the frame header and bound into the AEAD associated data. See [Protocol.md](Protocol.md) §5 and §7.

### 3.5 Messaging

```kotlin
interface MessagingService {
    suspend fun send(envelope: Envelope): Result<Unit>   // resolves, connects, seals, writes
    fun incoming(): Flow<Envelope>                        // decrypted application messages
    fun connectionEvents(): Flow<ConnectionEvent>
}
```

---

## 4. Addressing model

An `Endpoint` is a transport-tagged address, never an identity. Identities are addressed by their identity hash; endpoints are how a transport reaches a device right now.

```kotlin
data class Endpoint(
    val transport: TransportId,   // e.g. INTERNET, BLUETOOTH, WIFI_DIRECT
    val address: String,          // e.g. "ip:port" for INTERNET; opaque for others
    val expiresAt: Instant        // endpoints are ephemeral
)
```

Endpoints are produced by Discovery (for MVP, from signed DHT records) and consumed by Transport. Because endpoints are transport-tagged, the same identity can be reachable simultaneously over several transports.

### 4.1 Node addresses and pinned certificates

A relay or DHT node is addressed by a URL — `wss://host[:port][/path]` — optionally followed by the
keys its certificate may carry: `#pin-sha256=<pin>[,<pin>…]`, at most four, each the base64url
(no padding) SHA-256 of a DER `SubjectPublicKeyInfo`. `NodeUrl` (`core/common/.../network/NodeUrl.kt`)
is the one parser; the grammar is in [Protocol.md](Protocol.md) §19.

- **A pinned URL trusts its pins and nothing else.** A node set up on a bare IP has a self-signed
  certificate that no CA vouches for; its key is its identity. `PinnedTls` accepts the server when
  the leaf certificate's key matches a pin, and ignores its name and dates. An unpinned URL keeps the
  platform's CA validation, unchanged.
- **The pin never goes on the wire.** `NodeUrl.dialUrl` drops the fragment; the address string, pin
  and all, is what is stored, shared and signed.
- **One way to open a socket.** Every WebSocket to a node — a relay dial, the listener's control
  channel, a DHT request — goes through `WebSocketFrameClient.openWebSocket(url, targetIp, listener)`,
  which picks the client: pins, and, for relay sockets, the backend IP. Variants derive from one base
  `OkHttpClient` and share its connection pool.
- **Sticky IPs.** A relay name that resolves to several backends keeps a listener and its dialers on
  the same one: a relay socket dialled to one backend makes it, once it opens, the host's *sticky IP*
  (`RelayDns`), which later relay sockets try first. (This used to be called "pinning"; the word now
  means certificate keys. It was recorded from an OkHttp `EventListener`, which OkHttp does not give
  WebSocket calls, so until 2.0 it never actually took effect.)
- **Bounded.** Unpinned, untargeted sockets (most DHT requests) use the base client; pinned or
  targeted variants are kept in a 32-entry LRU, since their addresses can come from DHT peers. The
  shared dispatcher is uncapped: an open WebSocket holds its call for its whole life.

---

## 5. Transport abstraction and automatic selection

A `TransportSelector` chooses the best transport for a target. All registered `Transport`s are injected as a set (Hilt multibinding), so adding a transport requires no change to the selector.

```mermaid
flowchart TD
  Need["Need to reach identity hash X"] --> Resolve["Discovery.resolve(X)"]
  Resolve --> Endpoints["Candidate endpoints (per transport)"]
  Endpoints --> Filter["Keep endpoints whose transport canReach() = true"]
  Filter --> Rank["Rank by policy (cost, latency, power, reliability)"]
  Rank --> Try["Try best; fall back to next on failure"]
  Try --> Conn["Established Connection"]
```

Selection policy (MVP and beyond):

- Prefer already-open connections to the peer (connection reuse).
- Prefer local/offline transports when available and cheaper (future: Bluetooth/Wi-Fi Direct in the same room) over Internet.
- Prefer lower power and lower cost; degrade gracefully.
- On failure, transparently fall back to the next candidate endpoint/transport.

For the MVP only the Internet transport is registered, so selection trivially resolves to it; the machinery is in place so future transports activate automatically.

---

## 6. Internet transport (MVP)

- Reliable, ordered byte stream over TCP, carrying length-delimited frames (see [Protocol.md](Protocol.md)). TLS-style transport encryption is unnecessary because every frame is already end-to-end encrypted; the Encryption layer authenticates the peer by identity key, which is stronger than CA-based TLS for this use case.
- Listens on a local port and registers its address as an `Endpoint` published via the DHT discovery provider.
- Connection reuse: an established connection is cached per peer and reused for subsequent messages and location packets.
- **Direct-first, relay-fallback:** the app tries direct `INTERNET` before `RELAY`. The built-in relay at `wss://relay.vmessenger.ir/relay` bridges opaque E2E-encrypted frames when direct connectivity fails and never decrypts them. UDP candidates are only tried when `P2PConfig.natTraversalEnabled` is on, which it is not by default.
- **Runtime flags (`core/common/.../network/P2PConfig.kt`):** endpoint resolution is cache-first and multiple bootstrap/relay nodes are health-ranked — both on by default. Peer exchange, embedded DHT participation, relay-peer mode, UDP attempts, store-and-forward and default-relay demotion (`reduceDefaultRelayEnabled`) all default to **false**; they are reachable code, not proven paths. See the "Known limitations" section of the [README](../README.md).
- DHT bootstrap and store/find use `wss://relay.vmessenger.ir/dht` through Arvan CDN + nginx TLS.
- Local emulator dev can use raw TCP bootstrap (`10.0.2.2:46555`) via `NetworkConfig.useDevBootstrap`.
- There is no NAT traversal: a UDP transport exists and TCP endpoints can be mirrored as UDP candidates, but there is no STUN/ICE candidate gathering, no hole punching and no connectivity checks, and the UDP path is off by default. Full Kademlia routing and replication are likewise not implemented.

---

## 7. Connection lifecycle

```mermaid
stateDiagram-v2
  [*] --> Resolving
  Resolving --> Connecting: endpoint found
  Resolving --> Failed: no endpoint
  Connecting --> Handshaking: channel open
  Connecting --> Failed: connect error
  Handshaking --> Active: secure session established
  Handshaking --> Failed: handshake/verify error
  Active --> Idle: no traffic
  Idle --> Active: new frame
  Idle --> Closing: timeout / app paused
  Active --> Closing: explicit close
  Closing --> [*]
  Failed --> [*]
```

- Resolving and connecting failures feed the retry/offline queue (see [Protocol.md](Protocol.md)).
- Idle connections are closed after a timeout to save battery; the next message re-establishes them.

---

## 8. Resilience and failure handling

- Resolution failure (peer offline / no fresh DHT record): message stays in the offline queue; the app periodically re-resolves and retries.
- Connection failure: try the next candidate endpoint/transport; if all fail, back off with jitter and requeue.
- Handshake/verification failure: treated as a security event, not retried blindly; surfaced to the user if the peer key mismatches (possible MITM or key change). See [Security.md](Security.md).
- Mid-session drop: sessions are never persisted, so the next send runs a fresh handshake. Messaging is at-least-once with per-conversation `message_id` deduplication, so nothing is lost or applied twice.

---

## 9. How future transports plug in

To add Bluetooth, Wi-Fi Direct, or mesh:

1. Create a `:network:transport-<name>` module implementing `Transport` and `Connection`.
2. Provide `TransportCapabilities` (reachability class, MTU, ordering/reliability) so the selector can rank it.
3. Register it via a Hilt `@IntoSet` binding.
4. Optionally add a matching `DiscoveryProvider` (for example, BLE advertisement scanning) that produces endpoints tagged with the new transport.

No changes are required in Encryption, Messaging, Domain, or UI. That is the practical payoff of the layered design; no such transport module exists today.
