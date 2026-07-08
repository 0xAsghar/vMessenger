# vMessenger P2P Migration Phases

This document defines a staged plan for moving vMessenger from the current relay-assisted design toward a more decentralized, serverless peer-to-peer network.

The goal is not to remove the current relay immediately. The safe goal is to reduce dependency on any single relay or bootstrap server while keeping the app usable at every step.

## Guiding Principle

The current relay server performs two important jobs:

- Discovery: helping peers find current endpoints for an identity hash.
- Relay transport: forwarding encrypted frames when direct peer-to-peer connectivity fails.

Both jobs must be replaced gradually. Removing the relay before replacement mechanisms are stable would make the app unreliable for most real-world mobile users, especially users behind carrier-grade NAT, restrictive Wi-Fi, or changing mobile networks.

Every phase below should be guarded by feature flags, measured in debug tooling, and tested on real devices before becoming the default.

> **Note:** v0.2.0 shipped app-layer features (display names, `ContactRequest` mutual approval, MapLibre location) independently of P2P phases below. See [Architecture.md](Architecture.md) and [Roadmap.md](Roadmap.md).

## Implementation status (v0.2.0)

The Android app has started implementing this migration, but the phases are not all complete yet. Runtime flags live in `P2PConfig` (`core/common`). The current defaults enable the experimental paths, so use **تنظیمات → اشکال‌زدایی → P2P migration flags** to turn individual phases off during conservative testing.

Current status from the codebase:

- Phase 0: implemented. The existing relay remains available as a fallback path.
- Phase 1: implemented for database-backed bootstrap/relay node lists, health ordering, and relay rotation.
- Phase 2: mostly implemented. Users can add, remove, enable/disable, import, export, and QR-share nodes.
- Phase 3: partially implemented. Verified endpoint records can be cached and reused, and learned DHT node addresses are persisted.
- Phase 4: partially implemented. Peers exchange bootstrap/relay node hints after handshake, but the exchanged records are simple address hints, not signed node capability records.
- Phase 5: partially implemented. Android clients can run a minimal TCP DHT service, but it is in-memory, limited, and not a full replicated DHT routing node.
- Phase 6: not fully implemented. The app advertises a `relay-peer` capability, but Android clients do not yet implement third-party relay circuit bridging with limits, consent policy, and abuse controls.
- Phase 7: not fully implemented. A UDP transport exists and TCP endpoints can be mirrored as UDP candidates, but there is no full ICE/STUN/UDP hole-punching implementation yet.
- Phase 8: partially implemented. Mailbox blob storage and offering pending blobs during sessions exist, but there is not yet a full trusted-peer mailbox discovery/pull protocol.
- Phase 9: partially implemented. Endpoint ordering demotes the default relay behind direct/user relay paths, but this depends on the incomplete phases above.

Recent reliability fixes in the current code:

- Relay fallback when DHT lookup returns no peer endpoints, useful for cold start or new contacts.
- Ratchet receive state does not advance on failed decryption.
- Post-handshake hooks run before the first normal encrypted send/read on a session.

See [README.md](../README.md#how-to-run-a-node) for operating bootstrap/relay nodes.

For known gaps, risks, and the prioritized fix list see [P2P-Bugs-Improvement.md](P2P-Bugs-Improvement.md).

## Phase 0: Keep The Existing Relay As Fallback

Keep `relay.vmessenger.ir` working while the decentralized pieces are added.

This phase is mostly a safety rule. The existing relay should remain available as the final fallback path for discovery and message transport while new P2P behavior is introduced.

Expected behavior:

- Existing users can still pair and message as before.
- Production messaging remains functional if new experimental P2P paths fail.
- Debug screens clearly show which path was used: direct, relay, cached peer, community node, or user relay.

Why this matters:

Pure P2P networking is difficult on mobile networks. If the app removes the relay first, two phones may fail to discover each other or connect through NAT. Keeping the relay preserves a working baseline.

## Phase 1: Support Multiple Bootstrap And Relay Nodes

Replace the single hardcoded bootstrap/relay dependency with a list of known nodes.

Instead of treating `relay.vmessenger.ir` as the only production infrastructure, the app should support multiple bootstrap and relay endpoints. These can be built-in community nodes, user-added nodes, or nodes imported through QR codes.

Implementation goals:

- Store multiple bootstrap nodes in the local database.
- Store multiple relay nodes in the local database.
- Try nodes in priority order.
- Mark nodes healthy or unhealthy based on recent success.
- Rotate away from failing nodes automatically.
- Keep `relay.vmessenger.ir` as one entry, not the only entry.

Expected behavior:

- If one node is down, the app tries another.
- Users can add their own node without rebuilding the app.
- The app no longer has a single operational point of failure.

Security requirements:

- Nodes must never be trusted with plaintext.
- Endpoint records must remain signed by the user's identity key.
- The app must verify records regardless of which node returned them.

## Phase 2: User-Configurable Nodes

Allow users to add, remove, import, and export bootstrap or relay nodes.

This turns infrastructure from app-owned into community-operated. A family, company, city, or community can run its own node and share it with members.

Implementation goals:

- Add a Settings screen for node management.
- Support node import through QR code or text link.
- Support node export through QR code.
- Let users disable built-in nodes.
- Show node health, last successful connection, and failure reason.

Expected behavior:

- Advanced users can self-host.
- Communities can share node lists.
- The app can survive if the default public node disappears.

Important limitation:

This is still not fully serverless. It is decentralized infrastructure. That is a necessary intermediate stage before ordinary mobile clients can participate in discovery and relaying.

## Phase 3: Cache Previously Seen Peers

Use locally cached peer information before asking public bootstrap infrastructure.

When the app successfully connects to peers or receives valid DHT nodes, it should remember them. On the next launch, it should try cached nodes first.

Implementation goals:

- Persist known peer endpoints with expiry times.
- Persist known DHT nodes separately from contacts.
- Track last success and failure counts.
- Remove stale or repeatedly failing entries.
- Try cached peers before built-in bootstrap nodes.

Expected behavior:

- The app can rejoin the network from previously known peers.
- Bootstrap nodes become less important over time.
- Small communities become more self-sustaining.

Security requirements:

- Cached data must expire.
- Cached endpoint records must still be signature-verified.
- A cached endpoint must not override a newer signed record with a higher sequence number.

## Phase 4: Peer Exchange After Successful Handshake

Let connected peers exchange known network nodes.

After two users establish an encrypted session, each side can share a small list of known bootstrap-capable or relay-capable peers. This helps the network spread without a central directory.

Example flow:

```text
Alice connects to Bob.
Alice sends 20 known healthy nodes.
Bob sends 20 known healthy nodes.
Both verify, rank, and cache the received nodes.
```

Implementation goals:

- Add a protocol message for peer exchange.
- Exchange only network node records, not contact lists or private social graph data.
- Limit the number of peers shared per session.
- Score received nodes cautiously until they prove reachable.
- Avoid sharing sensitive local-only endpoints unless the user allows it.

Expected behavior:

- Users gradually learn about more network nodes.
- The network can recover even if original bootstrap nodes disappear.
- Discovery becomes more organic and less centralized.

Privacy concerns:

Peer exchange can leak metadata about which nodes a user knows. Keep shared records minimal and avoid sharing contact-specific information.

## Phase 5: Make Android Clients DHT Participants

Move minimal DHT server behavior into the Android app.

Currently, the Android app behaves mostly like a DHT client. To become more serverless, online users should also be able to store and return signed endpoint records for other users.

Implementation goals:

- Add an embedded DHT service to the Android app.
- Support `PING`.
- Support `STORE` for signed endpoint records.
- Support `FIND_VALUE` for identity-hash lookup.
- Verify endpoint record signatures before storing.
- Expire records by TTL.
- Enforce storage limits.
- Disable or reduce participation on low battery or metered networks.

Expected behavior:

- Online users help discovery for other users.
- Bootstrap servers become less necessary after the network is formed.
- The app becomes closer to a real peer-to-peer DHT.

Risks:

- Battery usage can increase.
- Mobile OS background limits may stop participation.
- NAT may prevent many phones from accepting inbound DHT requests.
- Abuse protection becomes necessary.

Recommended safety controls:

- Feature flag this phase.
- Default to Wi-Fi and charging only at first.
- Add rate limits.
- Add maximum stored records.
- Add maximum DHT requests per minute.
- Let users opt out.

## Phase 6: Add Relay-Capable Peer Mode

Allow online users to relay encrypted frames for other users.

This is the phase where ordinary users can play the role currently played by the relay server. A relay-capable peer does not decrypt messages. It only forwards opaque encrypted frames between two other peers.

Example:

```text
Alice cannot reach Bob directly.
Alice finds Charlie, who is relay-capable.
Alice opens a circuit through Charlie to Bob.
Charlie forwards encrypted frames.
Bob decrypts the messages locally.
```

Implementation goals:

- Add relay capability to signed endpoint records.
- Add relay circuit protocol messages.
- Support `RelayOpen`, `RelayReady`, `RelayData`, and `RelayClose`.
- Limit max circuits per device.
- Limit bandwidth per circuit.
- Limit total relay bandwidth.
- Allow relay mode only when user settings permit it.

Suggested user settings:

- Off.
- Contacts only.
- Everyone while charging.
- Everyone while charging and on Wi-Fi.
- Custom bandwidth limit.
- Custom max circuit count.

Security requirements:

- Relayed content must already be end-to-end encrypted.
- Relay peers must not be able to impersonate the destination.
- Relay peers must not be allowed to proxy arbitrary internet traffic.
- Relay frames must be restricted to the vMessenger protocol.
- Circuits must expire automatically.

Expected behavior:

- If direct connection fails, the app can try user-operated relays.
- Community relays become optional rather than required.
- The central relay becomes one possible relay among many.

Risks:

- Relay peers learn some metadata: who connected to them and roughly how much traffic was forwarded.
- Users may experience battery and data usage.
- Attackers may try to abuse relay mode for traffic amplification.

This phase must ship slowly and conservatively.

## Phase 7: Add NAT Traversal And Direct UDP Transport

Improve direct peer-to-peer connectivity so relays are needed less often.

Many mobile users cannot accept direct TCP connections. NAT traversal increases the chance that two peers can connect without a relay.

Implementation goals:

- Add UDP transport.
- Implement ICE-like candidate gathering.
- Try IPv6 direct addresses.
- Try LAN discovery where available.
- Try UDP hole punching.
- Optionally use UPnP or NAT-PMP on trusted networks.

Expected behavior:

- More conversations use direct P2P.
- Relay usage drops.
- Latency and bandwidth costs improve.

Important limitation:

Some networks, especially carrier-grade NAT and restrictive mobile providers, will still require relay fallback. NAT traversal reduces relay dependency; it does not eliminate it.

## Phase 8: Optional Store-And-Forward Through Trusted Peers

Add offline delivery without central message storage.

Live relays only work when sender, recipient, and relay are online at the same time. Offline delivery requires someone to temporarily store encrypted messages. This is more sensitive than live relaying and should come late.

Possible model:

```text
Alice encrypts a message for Bob.
Alice stores the sealed blob with one or more trusted mailbox peers.
Bob later asks known mailbox peers for sealed blobs addressed to him.
Bob decrypts locally.
Mailbox peers never see plaintext.
```

Implementation goals:

- Store only encrypted blobs.
- Use short TTLs.
- Require recipient identity hash or mailbox token.
- Limit storage size.
- Let users choose trusted contacts or community mailbox nodes.
- Add deletion after delivery or expiry.

Risks:

- Abuse through unwanted storage.
- Metadata leakage.
- Increased disk and bandwidth usage.
- More complex denial-of-service protection.

Recommendation:

Do not implement this until direct messaging, user relay mode, and DHT participation are stable.

## Phase 9: Reduce Default Relay Dependency

Only after the previous phases are stable, reduce reliance on the default relay.

The app can then prefer:

1. Cached direct endpoints.
2. Local network discovery.
3. DHT records from known peers.
4. User-operated relays.
5. Community relays.
6. The default relay as last resort.

Expected behavior:

- The app still works when the default relay is offline, as long as enough peers or community nodes are reachable.
- The default relay is no longer central infrastructure.
- Users and communities can operate the network themselves.

## Final Target Architecture

The final architecture should look like this:

```text
Identity
  -> signed endpoint and capability records
  -> cached peer table
  -> peer exchange
  -> user/community DHT nodes
  -> direct transport attempts
  -> NAT traversal
  -> user-operated relay fallback
  -> end-to-end encrypted messaging
```

The relay role does not disappear completely. Instead, it becomes a normal network role that many users or communities can provide.

## Definition Of Success

The migration is successful when:

- No single domain is required for the app to function.
- Users can self-host bootstrap and relay nodes.
- Online clients can participate in discovery.
- Some online clients can relay encrypted frames for others.
- Direct P2P is preferred whenever possible.
- Message content remains end-to-end encrypted in every path.
- The app remains usable for non-technical users.

## Non-Goals For The First Serverless Migration

These should not be attempted in the first migration:

- Removing all fallback infrastructure at once.
- Forcing every mobile phone to relay traffic.
- Storing offline messages on random peers by default.
- Allowing arbitrary internet proxying.
- Sacrificing battery life for network purity.

## Practical Recommendation

The safest path is progressive decentralization:

1. Keep the current relay working.
2. Add more nodes.
3. Let users configure nodes.
4. Cache peers.
5. Exchange peers.
6. Let clients participate in the DHT.
7. Let selected clients relay encrypted frames.
8. Add NAT traversal.
9. Reduce the default relay to a last-resort fallback.

This approach moves vMessenger toward a pure P2P network without breaking the app for current users.
