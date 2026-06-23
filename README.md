# vMessenger

> A fully decentralized, end-to-end encrypted messenger for Android. No accounts. No phone numbers. No central servers. Every device is a peer.

vMessenger is a privacy-first communication platform where each Android device is a sovereign peer that owns its own cryptographic identity. There is no backend to trust, no directory of users, and no company that can read, retain, or hand over your messages. Contacts are added only through QR codes or human-readable User Hashes, messages are end-to-end encrypted, and live location can be shared securely and revocably.

- Bundle ID: `ir.vmessenger.android`
- Platform: Android 8.0+ (API 26+)
- UI language: Persian (RTL), Material 3, light/dark
- Status: Design phase. This repository currently contains the architecture and protocol documentation only. Production code begins after these documents are approved.

---

## Vision

Build a messenger that cannot be shut down, censored, or surveilled from a single point, because there is no single point. The network is the sum of its users. Trust is rooted in cryptographic identity, not in a service provider.

Guiding values:

- Privacy by default. Plaintext never leaves the device; sensitive data at rest is encrypted.
- Self-sovereign identity. Your private key is your account, generated on-device and never transmitted.
- Decentralization without compromise. No central authentication, database, or message server.
- Calm, premium, minimal UX. The interface should make people feel safe, private, and in control.

---

## Core principles

- No user accounts, email, phone numbers, or usernames.
- No centralized authentication, database, or message relay.
- Identity is an on-device Ed25519 keypair. The public key derives a permanent identity hash and a human-readable User Hash.
- Contacts are added only via QR code or User Hash.
- The architecture is layered so each concern can be replaced independently: `Identity -> Discovery -> Transport -> Encryption -> Messaging`.
- The Discovery layer is fully independent from the Messaging layer.

---

## How it works (MVP)

The MVP is functional over the public Internet using a minimal Distributed Hash Table (DHT) for routing only.

1. Each device generates an Ed25519 identity locally.
2. Two users pair once by exchanging long-term public keys via QR or User Hash. No network needed for pairing.
3. To become reachable, a device joins the DHT through bootstrap nodes and publishes a signed, timestamped, expiring endpoint record (its current reachable address).
4. To start a conversation, a device looks up the contact's identity hash in the DHT, retrieves the signed endpoint record, and connects directly.
5. The two peers run an X25519 handshake, derive session keys with HKDF, and exchange ChaCha20-Poly1305 encrypted messages with forward secrecy and replay protection.

The DHT stores only temporary routing metadata. It never stores messages, contacts, private keys, or profiles.

```mermaid
sequenceDiagram
  participant A as Device A
  participant DHT as DHT / Bootstrap
  participant B as Device B
  Note over A,B: One-time pairing via QR or User Hash exchanges Ed25519 public keys
  B->>DHT: publish signed endpoint record, key = hash of B pubkey, with TTL
  A->>DHT: lookup hash of B pubkey
  DHT-->>A: signed endpoint record for B
  A->>B: direct connect, then X25519 handshake
  A->>B: ChaCha20-Poly1305 encrypted message
  B-->>A: delivery and read receipts
```

---

## MVP feature scope

- Identity generation (Ed25519) and human-readable User Hash.
- QR code pairing and User Hash pairing.
- Minimal DHT discovery: bootstrap, publish, lookup, TTL, refresh.
- End-to-end encrypted 1:1 messaging with delivery and read status.
- Retry queue and offline queue (store-and-forward on the sender side).
- Live Location sharing with a foreground service and encrypted location packets.
- Encrypted local storage (Room over SQLCipher) and contact management.

Designed for but intentionally deferred to later phases: groups, voice/video calls, file and image transfer, Bluetooth and Wi-Fi Direct transports, mesh networking, geofencing, location history analytics, SOS mode, team and family management, plugin system, and DHT/NAT/relay optimizations.

---

## Technology stack

- Language: Kotlin
- Architecture: Clean Architecture + MVVM
- UI: Jetpack Compose + Material 3 (Persian / RTL)
- DI: Hilt
- Async: Coroutines + Flow
- Database: Room over SQLCipher
- Serialization: Protocol Buffers (proto3)
- Crypto: Ed25519, X25519, ChaCha20-Poly1305, HKDF, SHA-256 (libsodium / BouncyCastle), Android Keystore for key wrapping

---

## Documentation index

Read these in order for a top-down understanding of the system.

- [docs/Architecture.md](docs/Architecture.md) - requirements analysis, Clean Architecture + MVVM, module map, DI, concurrency, end-to-end data flow.
- [docs/Network.md](docs/Network.md) - the layered networking model and automatic transport selection.
- [docs/Protocol.md](docs/Protocol.md) - wire format, Protobuf schemas, handshake, sessions, receipts, queues, versioning.
- [docs/Security.md](docs/Security.md) - cryptographic design, key management, forward secrecy, replay protection, threat model.
- [docs/Discovery.md](docs/Discovery.md) - the modular Discovery layer, QR and User Hash pairing, DHT-based resolution.
- [docs/DHT.md](docs/DHT.md) - the minimal DHT design, signed routing records, TTL and refresh, anti-centralization rules.
- [docs/Bootstrap.md](docs/Bootstrap.md) - the BootstrapProvider interface and how to operate bootstrap nodes.
- [docs/Database.md](docs/Database.md) - encrypted local schema, entities, DAOs, and migrations.
- [docs/UI.md](docs/UI.md) - Persian RTL design system, theme, and screen-by-screen specifications.
- [docs/FolderStructure.md](docs/FolderStructure.md) - the Gradle multi-module layout.
- [docs/Roadmap.md](docs/Roadmap.md) - the phased delivery plan from MVP to full feature set.

---

## Repository status and layout

```
vMessenger/
  app/                 <- Android application (Hilt, navigation, Splash)
  build-logic/         <- Gradle convention plugins
  core/                <- shared libraries (design system, database, proto, …)
  data/                <- repository implementations
  domain/              <- pure Kotlin domain layer
  feature/             <- feature UI modules (Compose)
  network/             <- networking stack modules
  docs/                <- architecture and protocol documentation
  vMessenger-icon/     <- app launcher icons and brand logos
```

Phase 1 (scaffolding + design system) is complete. See [docs/Roadmap.md](docs/Roadmap.md) for the next phases.

---

## Building and running

Requirements:

- JDK 17 (Gradle toolchain; CI uses Temurin 17)
- Android SDK 35 with Build Tools 35
- `local.properties` with `sdk.dir` pointing at your Android SDK

```bash
./gradlew assembleDebug
```

Install on a device or emulator:

```bash
./gradlew installDebug
```

Run static analysis and unit tests:

```bash
./gradlew detekt testDebugUnitTest
```

The app launches to a themed Splash screen, then Home with Persian RTL navigation. Theme mode (Light / Dark / System) can be changed under **تنظیمات** (Settings).

---

## Contributing and bootstrap nodes

vMessenger is designed to be community-operated. Anyone can run a bootstrap node or a DHT node; see [docs/Bootstrap.md](docs/Bootstrap.md). No bootstrap operator can read messages or identify users beyond ephemeral routing metadata, and the app never depends on a single bootstrap server.

---

## License

To be determined before the first public release. A permissive or copyleft open-source license is expected so the protocol and node software remain auditable and self-hostable.
