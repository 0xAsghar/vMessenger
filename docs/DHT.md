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
  R --> Conn["direct connection attempt"]
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
syntax = "proto3";
package vmessenger.dht.v1;

message EndpointRecord {
  bytes identity_hash = 1;     // SHA-256 of the publisher's Ed25519 public key = DHT key
  bytes identity_pub = 2;      // Ed25519 public key (lets verifiers check the signature)
  repeated Endpoint endpoints = 3;
  int64 published_at_unix_ms = 4;
  int64 ttl_ms = 5;            // record expires at published_at + ttl
  uint64 sequence = 6;         // monotonically increasing; newer replaces older
  bytes signature = 7;         // Ed25519 over all preceding fields
}

message Endpoint {
  string transport = 1;        // e.g. "INTERNET"
  string address = 2;          // e.g. "ip:port"
}
```

Record rules:

- `identity_hash` must equal `SHA-256(identity_pub)`; otherwise the record is invalid.
- `signature` must verify against `identity_pub`; otherwise the record is rejected. No node can forge or alter a record.
- A record is valid only while `now < published_at + ttl`. Default TTL is short (for example 10-30 minutes) so stale endpoints disappear quickly.
- `sequence` provides rollback protection: a node accepts a new record only if its sequence is greater than the stored one, preventing replay of an old endpoint.

---

## 3. Key space and node identity

- Keys and node IDs live in the same 256-bit space (SHA-256 output).
- A DHT node's ID is derived from its own key material; the DHT key for a user is their identity hash.
- Distance is the XOR metric (Kademlia), so "closest nodes to a key" is well-defined and records are stored on the nodes whose IDs are XOR-closest to the identity hash.
- This is the standard Kademlia foundation; only the minimal subset in Section 4 is implemented. There is no k-bucket routing table, no replication and no parallel lookup on the node side.

---

## 4. Minimal MVP operation set

The MVP implements exactly five operations - bootstrap, publish, lookup, TTL, refresh - and nothing more.

```mermaid
flowchart TD
  Boot["bootstrap: join via known nodes"] --> Ready["node has routing contacts"]
  Ready --> Pub["publish: store signed record on closest nodes"]
  Ready --> Look["lookup: find record by identity hash"]
  Pub --> TTL["TTL: records expire automatically"]
  Pub --> Refresh["refresh: republish before TTL expiry"]
  TTL --> Pub
  Refresh --> Pub
```

### 4.1 bootstrap

A bootstrap node is an ordinary DHT node with a stable, reachable address that a joining device contacts to obtain its first routing contacts. It is not a message server, not an authentication server, not a contact or identity server, and it holds no authority over records: records are Ed25519-signed and every client verifies them itself (Section 7). Once a device has usable contacts, the bootstrap node's role is finished.

Bootstrap addresses reach the client through `BootstrapProvider` implementations, injected as a Hilt multibinding set and merged by [`BootstrapManager`](../network/bootstrap/src/main/kotlin/ir/vmessenger/network/bootstrap/BootstrapManager.kt), which sorts providers by descending `priority` and de-duplicates by address. Two providers exist:

| Provider | Priority | Source |
|---|---|---|
| [`DatabaseBootstrapProvider`](../data/src/main/kotlin/ir/vmessenger/data/network/DatabaseBootstrapProvider.kt) | 200 | The enabled rows of the `bootstrap_node` table, healthiest first — built-in, user-added, imported and community nodes with their trust tier. Returns nothing while `P2PConfig.multiNodeEnabled` is off. |
| [`BuiltInBootstrapProvider`](../network/bootstrap/src/main/kotlin/ir/vmessenger/network/bootstrap/BuiltInBootstrapProvider.kt) | 100 | A single shipped default, `NetworkConfig.effectiveBootstrapAddress()`, kept as a guaranteed fallback entry. |

The design rule is that the app must never hard-depend on one operator: any sufficient set of nodes will do, and a user who adds their own node in **تنظیمات → گره‌های شبکه** (a `vmnode:bootstrap:…` link or its QR) is a first-class source. What the current build actually ships is one built-in node, which is recorded as a limitation in [Security.md](Security.md) Section 10.

`Dht.bootstrap(nodes)` in [`MinimalDht`](../network/dht/src/main/kotlin/ir/vmessenger/network/dht/MinimalDht.kt) sends a `Ping` to each candidate in turn, keeps the responders in `knownNodes`, and returns the responding subset so the caller can record per-node health and rotate away from unreachable nodes. Bootstrapping fails only when no candidate answers. Every address is gated by `NodeAddressPolicy` first: a release build dials `wss://` only, while `ws://` and bare `host:port` need a debug build and a local host.

Production bootstrap: `wss://relay.vmessenger.ir/dht` (WebSocket-secure through Arvan CDN + nginx TLS). Emulator development may use raw TCP `10.0.2.2:46555` via `NetworkConfig.useDevBootstrap`. Operating a node of your own is covered end to end in [Deployment.md](Deployment.md).

After a successful join the device keeps resolved records in a verified [`PeerEndpointCache`](../network/discovery/src/main/kotlin/ir/vmessenger/network/discovery/PeerEndpointCache.kt) and consults it before the network, so bootstrap availability affects the cold-join path rather than day-to-day messaging. A cached record never overrides a newer signed record.

### 4.2 publish (announce)

- Send the signed `EndpointRecord` to every reachable target with a `STORE` RPC; each target validates it and keeps it until TTL.
- One unreachable or policy-rejected target never aborts the store at the others. The publish fails only when no target was reachable, or when every reachable target rejected the record.
- Targets are the bootstrap addresses plus the nodes those returned. Closest-node selection over the XOR metric is the Kademlia design this leaves room for; `MinimalDht` does not implement it.

### 4.3 lookup (resolve)

- Ask each known target in turn for the identity hash until one returns a record; a failing target is skipped and only an all-fail run is an error. This is a flat sweep, not the iterative closest-node search of full Kademlia.
- The client verifies signature, identity-hash match, TTL, and sequence before using the endpoints ([`EndpointRecordVerifier`](../network/dht/src/main/kotlin/ir/vmessenger/network/dht/EndpointRecordVerifier.kt)).

### 4.4 TTL (expiry)

- Storing nodes drop records once expired. No central garbage collection; expiry is intrinsic to every record.

### 4.5 refresh (republish)

- The publisher republishes its record before TTL expiry (and immediately when its endpoints change, for example after a network switch), incrementing `sequence`.
- A background maintenance loop (see [Architecture.md](Architecture.md) Section 8) handles refresh while the app is active.

### 4.6 Minimal RPC surface

```proto
service DhtNode {
  rpc Ping(PingRequest) returns (PingResponse);              // liveness + routing-table refresh
  rpc FindNode(FindNodeRequest) returns (FindNodeResponse);  // closest known nodes to a key
  rpc Store(StoreRequest) returns (StoreResponse);           // store a signed EndpointRecord
  rpc FindValue(FindValueRequest) returns (FindValueResponse); // record if present, else closest nodes
}
```

These four RPCs are the classic Kademlia primitives; they are sufficient for bootstrap, publish, lookup, TTL, and refresh.

---

## 5. MVP participation model and reachability

A realistic, honest description of who does what in the MVP:

- Reachable nodes (public IP / port-forwarded), including community and self-hosted bootstrap nodes, act as full DHT nodes: they participate in routing and store records.
- Mobile devices behind NAT primarily act as DHT clients: they bootstrap, publish their own record, and perform lookups, but may not reliably serve as storage nodes until NAT traversal lands.
- Records are therefore stored predominantly on the reachable node set. Anyone can run a node and there is no single operator, and the design leaves room for a phone-inclusive DHT if NAT traversal lands.
- The reference node's record store is **in-memory** (`DhtRequestHandler` keeps a `ConcurrentHashMap`); a restart drops every record it held and devices re-announce within 10 minutes. There is no replication and `FIND_NODE` returns the configured peer nodes rather than the closest ones.
- Connectivity assumption: a successful lookup yields endpoints, but a direct connection still requires the target to be reachable at a published endpoint. Carrier-grade NAT traversal is not implemented; relay fallback is (see [Security.md](Security.md) "Known limitations").

This division keeps the description truthful: the network is internet-functional and self-hostable, while the hardest connectivity problems are named rather than hand-waved. See the "Known limitations" section of the [README](../README.md).

---

## 6. Anti-centralization rules

These rules are invariants, enforced in code and review:

- The DHT must never become a directory: no enumeration of all users, no search by name, no listing of records. Lookups require knowing the exact identity hash.
- No privileged nodes: bootstrap nodes have no special authority over records; they are ordinary DHT nodes that also serve as entry points.
- No operator can read or alter routing records (records are signed) and none can read messages (messages never touch the DHT).
- The app must function with any sufficient set of nodes and must not hard-depend on a specific operator's nodes.
- Record contents are minimized: only endpoints and the data needed to verify them.

---

## 7. Security

(See [Security.md](Security.md) for the full treatment.)

- Authenticity/integrity: every record is Ed25519-signed; tampering or forgery is detected.
- Rollback protection: monotonic `sequence` plus TTL.
- Sybil/eclipse resistance (MVP-level): use multiple bootstrap nodes and multiple independent lookups; require records from several closest nodes where possible; rate-limit and validate aggressively. Stronger eclipse defenses (node-ID derivation constraints, diversity heuristics) are future hardening.
- Resource protection: storing nodes bound record sizes and counts per key, apply per-source rate limits, and expire aggressively.
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
interface Dht {
    suspend fun bootstrap(nodes: List<BootstrapNode>): Result<Unit>
    suspend fun publish(record: EndpointRecord): Result<Unit>
    suspend fun lookup(identityHash: IdentityHash): Result<EndpointRecord?>
    fun maintenance(): Flow<DhtEvent>   // periodic refresh, bucket upkeep, expiry
}
```

The `Dht` lives in `:network:dht`; the `DhtDiscoveryProvider` in `:network:discovery` adapts it to the `DiscoveryProvider` contract (see [FolderStructure.md](FolderStructure.md)).
