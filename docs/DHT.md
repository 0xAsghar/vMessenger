# vMessenger - Distributed Hash Table (DHT)

The DHT is vMessenger's decentralized routing layer. Its single job is to map an identity hash to that peer's current, signed, expiring network endpoints. It is the mechanism that makes the MVP functional over the public Internet without any central server.

This document defines what the DHT stores (and never stores), the record format, the minimal MVP operation set, the key space, refresh/expiry semantics, anti-centralization rules, security, and the path to a full Kademlia implementation later.

Related: discovery flow in [Discovery.md](Discovery.md); joining the DHT is Section 4.1 below; record signing in [Security.md](Security.md); running a node of your own in [Deployment.md](Deployment.md).

---

## 1. Purpose and scope

The DHT exists to answer one question: "What are the current reachable endpoints for identity hash X?"

```mermaid
flowchart LR
  H["identity hash"] --> DHT["DHT"]
  DHT --> R["signed EndpointRecord (current endpoints)"]
  R --> Conn["connection attempt (direct, or through the named relay)"]
```

What the DHT stores:

- Signed, timestamped, expiring routing records: identity hash to endpoints.

What the DHT must never store:

- Messages, message content, or metadata about conversations.
- Contacts or social graph.
- Private keys or any secret material.
- User profiles, names, avatars, or any personal data.

The DHT is routing infrastructure, not a database and not a directory.

---

## 2. The routing record

```proto
// core/proto/src/main/proto/vmessenger/dht/v1/dht.proto
syntax = "proto3";
package vmessenger.dht.v1;

message EndpointRecord {
  bytes identity_hash = 1;     // SHA-256 of the publisher's Ed25519 public key = DHT key
  bytes identity_pub = 2;      // Ed25519 public key (lets verifiers check the signature)
  repeated Endpoint endpoints = 3;
  int64 published_at_unix_ms = 4;
  int64 ttl_ms = 5;            // record expires at published_at + ttl
  uint64 sequence = 6;         // monotonically increasing; newer replaces older
  bytes signature = 7;         // Ed25519 over the v2 transcript (Protocol.md §14)
  uint32 transcript_version = 8; // 2; 0 (0.x) is accepted by the node only
}

message Endpoint {
  string transport = 1;        // "INTERNET" or "RELAY"
  string address = 2;          // "ip:port" for INTERNET; a wss:// node URL for RELAY
}
```

Record rules:

- `identity_hash` must equal `SHA-256(identity_pub)`; otherwise the record is invalid.
- `signature` must verify against `identity_pub` over the domain-separated v2 transcript (`EndpointRecordTranscript`); otherwise the record is rejected. No node can forge or alter a record.
- A record is valid only while `now < published_at + ttl`. The app publishes with a 20-minute TTL (`DhtDiscoveryProvider.DEFAULT_TTL_MS`) so stale endpoints disappear quickly; verifiers refuse a TTL over 24 hours.
- `sequence` provides rollback protection: a node accepts a new record only if its sequence is greater than the stored one, preventing replay of an old endpoint.
- Storing nodes key records by the first 16 bytes of `identity_hash` (`IdentityHashMatcher.routingKeyHex`), so a lookup made from a User Hash, which carries only that prefix, still finds the record.

---

## 3. Key space and node identity

- Keys and node IDs live in the same 256-bit space (SHA-256 output).
- A DHT node's ID is derived from a random seed of its own (the reference node: `SHA-256("vmessenger-node-id" || seed)`, seed kept in `node.seed`); the DHT key for a user is their identity hash.
- Distance is the XOR metric (Kademlia), so "closest nodes to a key" is well-defined; in the Kademlia design records are stored on the nodes whose IDs are XOR-closest to the identity hash. `MinimalDht` does not select by distance (Section 4.2).
- This is the standard Kademlia foundation; only the minimal subset in Section 4 is implemented. There is no k-bucket routing table, no replication and no parallel lookup on the node side.

---

## 4. Minimal MVP operation set

The MVP implements exactly five operations - bootstrap, publish, lookup, TTL, refresh - and nothing more.

```mermaid
flowchart TD
  Boot["bootstrap: join via known nodes"] --> Ready["node has routing contacts"]
  Ready --> Pub["publish: store signed record on every known node"]
  Ready --> Look["lookup: find record by identity hash"]
  Pub --> TTL["TTL: records expire automatically"]
  Pub --> Refresh["refresh: republish before TTL expiry"]
  TTL --> Pub
  Refresh --> Pub
```

### 4.1 bootstrap

A bootstrap node is an ordinary DHT node with a stable, reachable address that a joining device contacts to obtain its first routing contacts. It is not a message server, not an authentication server, not a contact or identity server, and it holds no authority over records: records are Ed25519-signed and every client verifies them itself (Section 7). In the Kademlia design, once a device has usable contacts, the bootstrap node's role is finished; in `MinimalDht` the bootstrap nodes are also where records are stored and looked up (Sections 4.2 and 4.3), so they stay in use.

Bootstrap addresses reach the client through `BootstrapProvider` implementations, injected as a Hilt multibinding set and merged by [`BootstrapManager`](../network/bootstrap/src/main/kotlin/ir/vmessenger/network/bootstrap/BootstrapManager.kt), which sorts providers by descending `priority` and de-duplicates by address. Two providers exist:

| Provider | Priority | Source |
|---|---|---|
| [`DatabaseBootstrapProvider`](../data/src/main/kotlin/ir/vmessenger/data/network/DatabaseBootstrapProvider.kt) | 200 | The enabled rows of the `bootstrap_node` table, healthiest first — built-in, user-added, imported and community nodes with their trust tier. Returns nothing while `P2PConfig.multiNodeEnabled` is off. |
| [`BuiltInBootstrapProvider`](../network/bootstrap/src/main/kotlin/ir/vmessenger/network/bootstrap/BuiltInBootstrapProvider.kt) | 100 | A single shipped default, `NetworkConfig.effectiveBootstrapAddress()`, kept as a guaranteed fallback entry. |

The design rule is that the app must never hard-depend on one operator: any sufficient set of nodes will do, and a user who adds their own node in **تنظیمات → گره‌های شبکه** (a `vmnode:bootstrap:…` link or its QR, or a node the app sets up over SSH with **گرهٔ جدید** / "New node") is a first-class source. What the current build actually ships is one built-in node, which is recorded under "Known limitations" in the [README](../README.md).

At first run the app asks which node to use: the built-in test nodes (which the app says will be removed on 31 December 2026), an address of one's own, a node set up with New node, or none for now. Only "none" stops the built-in rows being seeded into `bootstrap_node` and `relay_node` (`DiscoveryRepositoryImpl.joinNetwork`); even then `BuiltInBootstrapProvider` contributes the built-in DHT address, and the built-in relay stays the fallback while no relay row is enabled (`RelayDirectoryImpl`, `NetworkConfig.relayFallbackEndpoints`).

`Dht.bootstrap(nodes)` in [`MinimalDht`](../network/dht/src/main/kotlin/ir/vmessenger/network/dht/MinimalDht.kt) sends a `Ping` to each candidate in turn, keeps the responders in `knownNodes`, and returns the responding subset so the caller can record per-node health and rotate away from unreachable nodes. Bootstrapping fails only when no candidate answers. Every address is gated by `NodeAddressPolicy` first: a release build dials `wss://` only, while `ws://` and bare `host:port` need a debug build and a local host.

Production bootstrap: `wss://relay.vmessenger.ir/dht` (WebSocket-secure through Arvan CDN + nginx TLS). Emulator development may use raw TCP `10.0.2.2:46555` via `NetworkConfig.useDevBootstrap`. Operating a node of your own is covered end to end in [Deployment.md](Deployment.md).

After a successful join the device keeps resolved records in a verified [`PeerEndpointCache`](../network/discovery/src/main/kotlin/ir/vmessenger/network/discovery/PeerEndpointCache.kt) and consults it before the network, so bootstrap availability affects the cold-join path rather than day-to-day messaging. A cached record never overrides a newer signed record.

### 4.2 publish (announce)

- Send the signed `EndpointRecord` to every reachable target with a `STORE` RPC; each target validates it and keeps it until TTL.
- One unreachable or policy-rejected target never aborts the store at the others. The publish fails only when no target was reachable, or when every reachable target rejected the record.
- Targets are the bootstrap nodes that answered the `PING` plus the nodes a `FIND_VALUE` miss named (only addresses `NodeAddressPolicy` admits; a learned node that fails three times in a row is dropped). Closest-node selection over the XOR metric is the Kademlia design this leaves room for; `MinimalDht` does not implement it.

### 4.3 lookup (resolve)

- Ask each known target in turn for the identity hash until one returns a record; a failing target is skipped and only an all-fail run is an error. This is a flat sweep, not the iterative closest-node search of full Kademlia.
- The client verifies the signature, that `identity_hash` is the SHA-256 of `identity_pub`, and the TTL before using the endpoints ([`EndpointRecordVerifier`](../network/dht/src/main/kotlin/ir/vmessenger/network/dht/EndpointRecordVerifier.kt)); an invalid record is skipped and the next target asked. `sequence` is enforced by storing nodes and the peer cache, not per lookup, and the returned record is not compared with the key that was looked up.

### 4.4 TTL (expiry)

- Storing nodes drop records once expired. No central garbage collection; expiry is intrinsic to every record.

### 4.5 refresh (republish)

- The publisher republishes its record before TTL expiry (and immediately when connectivity returns or the relay its listener is on changes), incrementing `sequence`.
- `EndpointAnnouncer` handles refresh: it re-announces every 10 minutes (half the 20-minute TTL), and after a failure backs off from 1 minute, doubling up to 10. It runs in the network foreground service (`NetworkLifecycleService`), so it keeps going while the app is in the background (see [Architecture.md](Architecture.md) Sections 8 and 9).

### 4.6 Minimal RPC surface

There is no gRPC service: each call is one `DhtRpcRequest` and one `DhtRpcResponse` over the `/dht` WebSocket (length-prefixed frames over plain TCP in dev), with the operation chosen by a `oneof` ([Protocol.md](Protocol.md) §14):

```proto
message DhtRpcRequest {
  oneof payload {
    PingRequest ping = 1;              // liveness; the node answers with its node id
    FindNodeRequest find_node = 2;     // known nodes for a key
    StoreRequest store = 3;            // store a signed EndpointRecord
    FindValueRequest find_value = 4;   // record if present, else known nodes
  }
}
```

These four operations are the classic Kademlia primitives; they are sufficient for bootstrap, publish, lookup, TTL, and refresh. The app's client sends `PING`, `STORE` and `FIND_VALUE`, and learns further nodes from the node list a `FIND_VALUE` miss returns; it never sends `FIND_NODE`.

---

## 5. MVP participation model and reachability

A realistic, honest description of who does what in the MVP:

- Reachable nodes (public IP / port-forwarded), including community and self-hosted bootstrap nodes, act as full DHT nodes: they participate in routing and store records.
- Mobile devices behind NAT primarily act as DHT clients: they bootstrap, publish their own record, and perform lookups, but may not reliably serve as storage nodes until NAT traversal lands. An embedded node for phones (`EmbeddedDhtService`: plain TCP on the listen port + 1000, up to 500 records in the `dht_record` table, a k = 8 bucket table, stores copied to two peers) exists behind `P2PConfig.dhtParticipationEnabled`, which is off by default; it is experimental and unverified.
- Records are therefore stored predominantly on the reachable node set. Anyone can run a node and there is no single operator, and the design leaves room for a phone-inclusive DHT if NAT traversal lands.
- The reference node's record store is **in-memory** (`DhtRequestHandler` keeps a `ConcurrentHashMap`); a restart drops every record it held and devices re-announce within 10 minutes. There is no replication and `FIND_NODE` returns the configured peer nodes rather than the closest ones.
- Connectivity assumption: a successful lookup yields endpoints, but a direct connection still requires the target to be reachable at a published endpoint. Carrier-grade NAT traversal is not implemented; relay fallback is (see [Security.md](Security.md) "Known limitations").

This division keeps the description truthful: the network is internet-functional and self-hostable, while the hardest connectivity problems are named rather than hand-waved. See the "Known limitations" section of the [README](../README.md).

---

## 6. Anti-centralization rules

These rules are invariants, enforced in code and review:

- The DHT must never become a directory: no enumeration of all users, no search by name, no listing of records. Lookups require knowing the identity hash (at least its 16-byte routing prefix).
- No privileged nodes: bootstrap nodes have no special authority over records; they are ordinary DHT nodes that also serve as entry points.
- No operator can alter routing records (records are signed; a node can read the ones it holds, see [Security.md](Security.md) L2) and none can read messages (messages never touch the DHT).
- The app must function with any sufficient set of nodes and must not hard-depend on a specific operator's nodes.
- Record contents are minimized: only endpoints and the data needed to verify them.

---

## 7. Security

(See [Security.md](Security.md) for the full treatment.)

- Authenticity/integrity: every record is Ed25519-signed; tampering or forgery is detected.
- Rollback protection: monotonic `sequence` plus TTL.
- Sybil/eclipse resistance is minimal: several bootstrap nodes can be configured, but a lookup accepts the first valid record any node returns and nothing requires agreement between nodes; nodes validate every record and rate-limit stores. Multiple independent lookups, records from several closest nodes and stronger eclipse defenses (node-ID derivation constraints, diversity heuristics) are future hardening.
- Resource protection: the reference node keeps one record per key (the newest sequence), caps the total (`VMESSENGER_MAX_RECORDS`, default 100 000) and the WebSocket frame size (1 MiB), rate-limits `STORE` per IP (60/min, burst 20), caps record TTL at 24 hours and sweeps expired records every minute.
- Privacy: a lookup reveals interest in an identity hash to the storing nodes - a documented MVP limitation, mitigated later by private-lookup techniques.

---

## 8. Future optimizations (post-MVP)

Designed-for, not built in the MVP:

- Full Kademlia routing table with k-buckets, bucket refresh, and replacement caches.
- Parallel iterative lookups (the `alpha` concurrency parameter) and adaptive timeouts.
- Record replication and re-publication across the closest k nodes for resilience.
- NAT traversal (hole punching) and relay-assisted reachability so phones become full nodes.
- Private/blinded lookups to reduce metadata exposure.
- Optional record types beyond endpoints (for example prekey bundles for asynchronous session setup), still signed and expiring. Not implemented.
- Mesh and offline DHT operation for transports without Internet.

---

## 9. Interface sketch

```kotlin
// network/dht/.../MinimalDht.kt
interface Dht {
    suspend fun bootstrap(nodes: List<BootstrapNode>): AppResult<List<BootstrapNode>>  // the responders
    suspend fun publish(record: EndpointRecord): AppResult<Unit>
    suspend fun lookup(identityHash: ByteArray): AppResult<EndpointRecord?>
    fun knownNodeAddresses(): Set<String>   // DHT nodes known to this client
}
```

There is no maintenance stream: refresh is `EndpointAnnouncer`'s job (Section 4.5) and expiry is the storing node's. The `Dht` lives in `:network:dht`; the `DhtDiscoveryProvider` in `:network:discovery` adapts it to the `DiscoveryProvider` contract (see [FolderStructure.md](FolderStructure.md)).
