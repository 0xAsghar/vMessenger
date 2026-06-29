# vMessenger P2P Bugs And Improvements

This document tracks correctness gaps, implementation risks, and next improvements for the P2P migration described in [P2P-Phases.md](P2P-Phases.md).

The goal is to keep the roadmap honest: some P2P pieces already have code support, but several are still partial and should not be treated as production-complete.

## 1. Documentation Overclaims

### Issue

Some documentation previously described phases 0-9 as fully implemented. Code review shows that several phases are partial or scaffolding-only.

### Current Reality

- Multi-node bootstrap and relay management exists.
- Peer exchange exists, but exchanges simple address hints.
- Embedded DHT exists, but is in-memory and not a full replicated DHT node.
- UDP transport exists, but full NAT traversal does not.
- Mailbox blob plumbing exists, but a complete trusted-peer mailbox protocol does not.
- Android user-operated relay bridging is not implemented yet.

### Improvement

Keep README, Roadmap, and P2P docs aligned with a status matrix:

- Implemented
- Mostly implemented
- Partially implemented
- Not implemented

Avoid saying "serverless" or "pure P2P" for the current rc until relay-independent operation is proven on real devices.

## 2. Phase 6: User Relay Mode Is Not Complete

### Issue

The app advertises a `relay-peer` capability when `P2PConfig.relayPeerModeEnabled` is true, but Android clients do not yet implement third-party relay circuit bridging.

Current code has:

- `PeerRelayCoordinator`
- `relay-peer` handshake capability
- central relay client/listener support

Missing code:

- Android-side relay circuit listener for third-party traffic
- `RelayOpen` / `RelayReady` / `RelayData` / `RelayClose` style protocol
- circuit table
- circuit TTL
- bandwidth limits
- max circuit limits
- user consent policy
- abuse/rate controls

### Risk

The UI/debug flag can make users believe their phone is relaying for others, but it is mostly a capability signal today.

### Improvement

Implement relay-capable peer mode as a real transport role:

1. Add explicit relay-circuit protobuf messages.
2. Add a `PeerRelayService` in the Android app.
3. Restrict relay traffic to vMessenger frames only.
4. Add user settings: off, contacts only, Wi-Fi only, charging only.
5. Add bandwidth and circuit limits.
6. Add debug visibility for active circuits.

## 3. Phase 7: UDP Exists, But NAT Traversal Does Not

### Issue

`UdpTransport` exists and direct TCP endpoints can be mirrored into UDP candidates, but this is not full NAT traversal.

Current code has:

- UDP connect/write/read support
- endpoint ordering for UDP when NAT traversal flag is enabled
- TCP endpoint mirroring as UDP candidates

Missing code:

- STUN-style public candidate discovery
- ICE-style candidate gathering and pairing
- UDP hole punching coordination
- connectivity checks
- NAT type detection
- fallback timing policy

### Risk

Calling this "NAT traversal" overstates what currently works. Basic UDP packets may work on some reachable networks, but this will not reliably connect two mobile devices behind NAT.

### Improvement

Rename the current behavior in code/docs as "UDP transport attempts" until real NAT traversal exists.

Then implement:

1. Candidate model: local, reflexive, relay.
2. Connectivity check protocol.
3. Coordinated simultaneous open through the DHT/relay.
4. ICE-like candidate ranking.
5. Metrics: direct TCP success, UDP success, relay fallback rate.

## 4. Phase 5: Embedded DHT Is Limited

### Issue

Android clients can run a minimal TCP DHT service, but it is in-memory and not a full DHT participant.

Current code has:

- `PING`
- `STORE`
- `FIND_VALUE`
- signature verification
- TTL expiry checks

Missing code:

- durable storage
- replication
- k-buckets or routing table
- parallel lookup
- record refresh
- resource limits
- mobile network/battery policy
- inbound reachability checks

### Risk

Most phones may not be reachable from the public internet, so embedded DHT participation may help only in limited cases unless paired with relay/NAT traversal.

### Improvement

Treat embedded DHT as experimental. Add:

1. Persistent record store with size limits.
2. Routing table.
3. Replication factor.
4. Health checks.
5. Battery/network gating.
6. Debug screen counters: stored records, served lookups, rejected records.

## 5. Phase 4: Peer Exchange Needs Stronger Records

### Issue

Peer exchange currently shares bootstrap and relay address strings. These are useful hints, but they are not signed capability records.

### Risk

Peers can feed low-quality or malicious node addresses. The app validates address format, but it does not authenticate node ownership or capability.

### Improvement

Move from address hints to signed node records:

- node address
- node role
- public key
- capabilities
- expiry time
- signature

Newly learned nodes should start with low trust and only become preferred after successful use.

## 6. Phase 8: Mailbox Store-And-Forward Is Partial

### Issue

Mailbox storage and offering pending blobs during an existing session exist, but there is no complete trusted-peer mailbox protocol.

Current code has:

- local mailbox blob table
- storing incoming mailbox blobs
- offering pending blobs after handshake

Missing code:

- selecting mailbox peers
- asking mailbox peers for pending blobs
- recipient authorization/token model
- quota policy
- sender retry integration
- deletion acknowledgements
- spam/abuse controls

### Risk

The feature can store and forward blobs in limited session-driven cases, but it is not yet a full offline delivery system.

### Improvement

Implement mailbox as an explicit protocol:

1. `MailboxPut`
2. `MailboxList`
3. `MailboxFetch`
4. `MailboxDelete`
5. quotas and TTLs
6. trusted-peer selection
7. encrypted payload validation

## 7. Phase 9 Depends On Incomplete Earlier Phases

### Issue

Endpoint ordering can demote the default relay, but reliable relay independence depends on phases 5, 6, 7, and 8.

### Risk

If the default relay is demoted too aggressively, message delivery may become less reliable before replacement paths are mature.

### Improvement

Keep default relay as last-resort fallback, but measure success rates before changing defaults:

- direct TCP success rate
- UDP success rate
- user relay success rate
- community relay success rate
- DHT cache hit rate
- mailbox delivery success rate

Only reduce default relay dependency when metrics prove alternatives work.

## 8. Feature Flags Are Runtime-Only

### Issue

`P2PConfig` flags are volatile runtime values. They are useful for debug sessions but may not persist across app restarts.

### Risk

Testing can be confusing because flags may reset to defaults after process restart.

### Improvement

Persist P2P flags in DataStore and expose reset-to-defaults in Debug.

Recommended fields:

- `multiNodeEnabled`
- `peerCacheEnabled`
- `peerExchangeEnabled`
- `dhtParticipationEnabled`
- `relayPeerModeEnabled`
- `natTraversalEnabled`
- `storeAndForwardEnabled`
- `reduceDefaultRelayEnabled`

## 9. Relay Directory Publishing Should Be Verified

### Issue

`RelayDirectoryImpl` updates `NetworkConfig.relayAddress`, and publishing uses `NetworkConfig.effectiveRelayEndpoint()`. This coupling should be carefully tested.

### Risk

If publish runs before relay directory selection, the app may publish the default relay while listening on another relay, or vice versa.

### Improvement

Make relay selection explicit in the publish flow:

1. select active relay
2. start listener on selected relay
3. publish the same selected relay endpoint
4. record success/failure for that relay

Add a test to prove the published relay endpoint matches the active listener URL.

## 10. Debug Path Tracking Needs More Coverage

### Issue

`NetworkPathTracker` records successful send paths, but more detailed diagnostics are needed for P2P migration.

### Improvement

Track:

- attempted endpoints in order
- failed endpoints and failure reason
- successful endpoint
- whether endpoint came from cache, DHT, peer exchange, or default fallback
- active relay URL
- active bootstrap URL
- DHT participant status
- relay-peer status
- mailbox pending count

This will make real-device testing much easier.

## 11. Security Review Needed Before Enabling User Relay By Default

### Issue

User relay mode introduces abuse and metadata risks.

### Risks

- traffic amplification
- battery drain
- data usage
- metadata exposure
- unwanted third-party traffic
- relay abuse by unknown peers

### Improvement

Before enabling real user relay mode by default:

1. Require explicit user consent.
2. Default to off or Wi-Fi + charging only.
3. Add circuit limits.
4. Add bandwidth limits.
5. Add blocklist/rate-limit behavior.
6. Never relay arbitrary internet traffic.
7. Show relay activity in Debug.

## 12. Testing Plan

Before claiming pure P2P behavior, test these scenarios:

- two emulators through local TCP DHT
- two physical devices on same Wi-Fi
- two physical devices on different Wi-Fi networks
- one Wi-Fi device and one mobile-data device
- both devices on mobile data
- default relay disabled
- all built-in nodes disabled, user node enabled
- cached peer only
- peer exchange only
- relay-peer mode with three devices
- mailbox delivery with recipient offline

Each scenario should record:

- discovery path
- transport path
- handshake success/failure
- message delivery status
- battery impact
- logs from both devices

## Priority Fix List

Recommended order:

1. Correct docs to avoid overclaiming full P2P completion.
2. Persist P2P flags.
3. Add tests for relay selection vs published endpoint.
4. Rename current Phase 7 behavior to UDP attempts until real NAT traversal exists.
5. Implement real user relay circuit bridging.
6. Add relay consent, limits, and debug visibility.
7. Make embedded DHT durable and resource-limited.
8. Upgrade peer exchange from address hints to signed node records.
9. Define the mailbox protocol.
10. Add real-device P2P test matrix.
