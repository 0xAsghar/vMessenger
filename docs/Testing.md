# vMessenger - Testing

How the project is verified: unit tests, the node's own tests, the two-emulator integration procedure, the scenario matrix that was actually executed for the Milestone 3 security rewrite, and release verification.

This file replaces the old `P2P-Testing.md`.

---

## 1. Unit tests

```bash
./gradlew unitTests
```

`unitTests` is a root aggregate task (`build.gradle.kts`) that depends on `testDebugUnitTest` in every Android module **and** `test` in every module applying the Kotlin JVM plugin — today `:core:common`, `:domain` and `:node`. A plain `./gradlew testDebugUnitTest` silently skips those three, which is why the task exists and why both CI and the release workflow call it.

Static analysis runs alongside:

```bash
./gradlew detekt unitTests
```

Detekt is applied to every subproject from the root build with a shared config (`config/detekt/detekt.yml`) and a per-module baseline (`<module>/detekt-baseline.xml`) — pre-existing structural findings live in the baseline, new code must stay clean.

### Where the tests live

84 test files across 13 modules:

| Module | Focus |
|---|---|
| `:core:common` | `Canonical` encodings, `EndpointRecordTranscript`, `RelayProof`, `UserHashEncoder`, `IdentityHashMatcher`, `NodeRanking`, `NodeAddressPolicy`, `KeyedMutex`, `NetworkPathTracker` |
| `:core:crypto` | `LazysodiumCryptoEngine`, `WrappedKeyBlob`, `PairingDescriptorCodec`, `BackupBundleCodec` |
| `:core:database` | `DatabaseKeyProvider` (passphrase caching/concurrency, without the Android Keystore); `MigrationTest` |
| `:core:datastore`, `:core:designsystem` | preference defaults, design tokens |
| `:network:messaging` | `HandshakeTranscriptTest`, `SecureChannelFactoryTest`, `SymmetricRatchetTest`, `MessagingServiceFrameGuardTest`, `MessagingServiceKeyChangeTest`, `MessagingServiceConcurrencyTest`, `MessagingServiceProvisionalContactTest`, `FrameParserFuzzTest`, `RelayHelloProofInteropTest`, `RelayListenerTest`, `PeerRelayServiceTest`, `EndpointOrderingTest` |
| `:network:dht`, `:network:discovery`, `:network:transport` | record verification, embedded-DHT routing, endpoint resolution, transport selection |
| `:data` | inbound policy and collector, receipts, attachments, contact requests, mailbox seal/protocol, outbox error codes, relay selection vs. published endpoint, signature domain separation, backup, wipe plan, group control authority, per-recipient delivery and its aggregate |
| `:domain` | use cases |
| `:feature:identity` | ViewModel |
| `:node` | see §2 |

There are **no** `androidTest` (instrumented) sources in the repository; everything runs on the JVM.

### Migrations are replayed on a real SQLite engine

`core/database/src/test/.../migration/MigrationTest.kt` runs **every** migration 1 → 18 against an in-memory SQLite through `sqlite-jdbc`, then asserts the results of the newest step: the re-keyed `outbox`, the dropped `session` table, the now-nullable `conversation.contactId`, the unique-per-group constraint and the new `message` columns.

It needs no emulator and no Room. `JdbcSupportDatabase` builds a `SupportSQLiteDatabase` as a `java.lang.reflect.Proxy` that implements exactly one method — `execSQL` — and fails loudly on anything else; that is all a `Migration` ever calls, and it keeps the helper to a few lines instead of stubbing a ~50-method interface.

This exists because Room does not type-check migration SQL. Before this test, a typo or an invalid `ALTER` was only discovered when a user's app failed to open its database. Migration 17 → 18 recreates three tables and re-keys a queue, so that risk was no longer acceptable.

**When you change an entity, add the migration and extend this test in the same commit.** Room will regenerate `schemas/<version>.json` on build; copy the generated `createSql` into the migration so the two cannot drift.

### The V1 regression suite

2.0 has to go on working with phones still running 1.1.2, and with the data 1.1.2 left on a phone that updates. These tests hold that, each against 1.1.2 itself rather than anyone's reading of it:

| Test | What it holds |
|---|---|
| `core/proto` `V1WireCompatibilityTest` | 1.1.2's `.proto` files, copied verbatim from the `v1.1.2` tag into `src/test/resources/v1.1.2`, against the schema this build compiles: every field 1.1.2 knows keeps its number, type, cardinality and oneof, no number is removed unless reserved, no reserved number is reused, and every enum value 1.1.2 can send is still defined |
| `core/proto` `V1WireRoundTripTest` | real bytes both ways, using 1.1.2's `messaging.proto` compiled under another package (`src/test/proto/v112`, kept identical to the released text but for its two package lines): a 1.1.2 message, file, receipt and group read intact and untimed; a timed message, an album image and a group with roles reach 1.1.2 as the message, image and group it knows; a call, a location request and a role change are nothing 1.1.2 can misread |
| `core/database` `V1DatabaseUpgradeTest` | a database exactly as a fresh 1.1.2 install created it — from Room's own schema 20 (`schemas/…/20.json`), not rebuilt through the migration chain — with one fully filled row in every table, upgraded by 20 → 24: every row survives value for value, the result is exactly schema 24, and what 2.0 added reads as absent rather than zero |
| `data` `V1PeerCompatibilityTest` | what 2.0 does with it: a 1.1.2 message is kept and no expiry sweep takes it; a group created on 1.1.2 arrives with plain members and audit retention off |
| `core/common` `UserHashEncoderTest` | a `vm2-` ID, as 1.1.2 wrote them, still decodes to the same identity |

Each was checked by breaking what it guards: renumbering one 1.1.2 field fails the schema test, and dropping the last migration fails the database test.

### Running a subset

```bash
./gradlew :network:messaging:testDebugUnitTest --tests '*SymmetricRatchetTest'
./gradlew :node:test --tests '*RelayNodeServerTest'
./gradlew :core:common:test
```

`scripts/p2p-terminal-check.sh` runs a fixed P2P-focused subset and then curls a node's `/healthz`.

---

## 2. Node tests

The reference node is a plain JVM module, so:

```bash
./gradlew :node:test
```

| Test | Covers |
|---|---|
| `RelayNodeServerTest` | End-to-end `/relay` behaviour with Ktor `testApplication`: listener registration, the DIALER→INCOMING→ACCEPT→READY circuit, and the literal rejection strings (`Peer not listening on relay`, `Relay accept timed out`, `Rate limited`) |
| `DhtRequestHandlerTest` | `PING` / `STORE` / `FIND_VALUE` handling, record acceptance and rejection |
| `ReplayCacheTest` | TTL expiry, capacity eviction, replay detection |
| `RateLimiterTest` | token-bucket rate/burst behaviour |
| `NodeConfigTest` | env parsing and fallback to defaults on unset/malformed values |
| `NodeIdentityTest` | persisted node seed |
| `NodeStatsTest` | counter JSON |
| `ClientIpTest` | `VMESSENGER_TRUST_PROXY` / `X-Forwarded-For` handling |

Run the node locally:

```bash
./gradlew :node:run --args="--tcp"     # plain TCP DHT on :46555 (dev)
./gradlew :node:installDist            # -> node/build/install/node/bin/node
```

Production deployment is in [Deployment.md](Deployment.md).

---

## 3. Two-emulator integration test

Two emulators cannot reach each other directly. A host DHT node plus `adb` port forwards bridges them.

### 3.1 Environment

```bash
export ANDROID_SDK_ROOT="$PWD/.android-sdk"
export JAVA_HOME="$PWD/.jdk/jdk-17/Contents/Home"   # Gradle auto-provisions JDK 21 for compilation;
                                                    # apksigner also needs JAVA_HOME
```

Background shells do not inherit these from the user profile — export them explicitly.

### 3.2 AVDs

```bash
avdmanager create avd -n Pixel_8 -k "system-images;android-35;default;arm64-v8a" -d pixel_6
avdmanager create avd -n TestB   -k "system-images;android-35;default;arm64-v8a" -d pixel_6
```

The `pixel_8` device profile does not exist in this SDK; `pixel_6` gives 1080x2400. Without `-d`, an AVD defaults to 320x640 and every tap coordinate differs from the other device. AVDs under `~/.android/avd` get wiped occasionally — recreate them when the directory is empty.

```bash
emulator -avd Pixel_8 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5554 &
emulator -avd TestB   -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -port 5556 &
```

### 3.3 Host node and port forwards

```bash
./gradlew :node:run --args="--tcp"                       # DHT on :46555
adb -s emulator-5554 forward tcp:48555 tcp:48555         # device A
adb -s emulator-5556 forward tcp:48666 tcp:48555         # device B
```

`./scripts/emulator-connect.sh [listen_port] [forward_port]` does the same for a single attached device. From inside an emulator the host node is `10.0.2.2:46555`.

### 3.4 Install and point a device at the dev bootstrap

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Debug and locally built release APKs share `~/.android/debug.keystore`; a build signed with a *previous* debug key fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and needs `adb uninstall ir.vmessenger.android` first. After `pm clear`, the notification-permission dialog (ALLOW) appears on first launch.

`NetworkLifecycleService` is not exported, so switching a device to the dev bootstrap needs `adb root` first:

```bash
adb -s emulator-5554 shell am start-foreground-service \
  -n ir.vmessenger.android/ir.vmessenger.app.network.NetworkLifecycleService \
  --ei listen_port 48555 --ei forward_port 48555 --ez use_dev_bootstrap true
```

Device B uses `--ei forward_port 48666`.

### 3.5 Reading logs

App logs are written to `/data/data/ir.vmessenger.android/files/logs/*.log` (readable with `adb root`). Useful tags: `Network`, `Discovery`, `Dht`, `Relay`, `Messaging`, `Contact`, `Outbox`, `Ratchet`, `Attachment`, `Backup`, `Identity`, `Wipe`.

### 3.6 Driving the UI

`uiautomator dump` plus a small helper that prints the tap centre for a text or class match (it resolves to the enclosing clickable). Dialogs shift when the IME opens — re-dump before each tap. RTL `AlertDialog` buttons land far to the left.

Typical flow: Intro "شروع" → name field → "ساخت هویت" → the hash text matches `^vm-` → "ادامه"; Contacts FAB → hash field → "افزودن"; on the peer, the overlay's "تأیید".

### 3.7 Two traps

- **Restoring the same backup on both emulators gives both the same identity**, which makes them fight over one relay listener slot (an endless `replaced=true` loop). Create a distinct identity on the second device afterwards.
- Production relay checks do not need emulators at all; a stdlib WebSocket client against `wss://relay.vmessenger.ir/relay` is enough.

---

## 4. Milestone 3 scenario matrix (executed)

This is what was actually run on the two emulators for the protocol-v2 / security rewrite, and the observed result. Source: the M3 verification table in the project plan, completed 2026-09-12.

| Scenario | Result |
|---|---|
| Identity v2 | `vm-` hashes on both devices |
| Handshake v2 | `handshake v2 ok … dh=3` in both roles (initiator and responder) |
| Pairing by hash | request → approve → mutual `APPROVED` over the relay |
| Provisional rebind | `inbound session rebound to contact=…`; first post-approval message delivered with no rejection; receipt in ~200 ms (previously ~18 s) |
| Delivery receipt | `receipt RECEIPT_TYPE_DELIVERED`, bubble shows "تحویل شد" |
| Read receipts | batched `read receipt queued … count=2`; both `RECEIPT_TYPE_READ`; bubbles show "خوانده شد" |
| Receipt routing | `receipt sent on inbound session` / `on new session` — never dials inline |
| Attachment transfer | `sent (1 chunk(s), 7858 bytes)` → `transfer complete … sha256 ok` |
| Attachment encryption at rest | stored file starts with `VMA1` (not the PNG magic); 7919 B stored vs. 7858 B plaintext |
| DHT re-announce | `re-announce endpoints OK` on both devices; the node's `dhtRecords` counter non-zero (was 0) |
| Boot restart | after `adb reboot` the service auto-started **without opening the app**; `types=0x00000200` (remoteMessaging FGS), `vis=PRIVATE`; reconnected and re-announced |
| Secure wipe | database back to 4096 B, attachments gone, log reset, process restarted, app opens at onboarding with no identity |
| R8 release build | installs and runs clean across all screens, no crash |
| StrongBox fallback | `StrongBox key generation failed, falling back to TEE` on the emulator — the fallback path works |
| DB passphrase race | `database passphrase needed before init finished; loading it synchronously` — the fallback works, no crash |

**Not verifiable on-device at that point (no UI yet; covered by unit tests instead):** blocked-contact drop, forged-receipt rejection, key-change banner.

**Limitation recorded during the run:** after a secure wipe, the `AlarmManager` relaunch does not bring the app to the foreground (Android's background-activity-start restriction). The data is destroyed and the service restarts; the user taps the launcher icon and lands on onboarding.

---

## 5. Release verification

Both workflows live in `.github/workflows/`.

### CI (`ci.yml`, every push/PR to `main`)

```
detekt → assembleDebug + :node:installDist → unitTests
```

### Release (`release-apk.yml`)

Triggered by a `v*` tag (publishes) or by a change to `gradle/version.properties` on `main` (build-only check).

1. **Tag must match `versionName`** in `gradle/version.properties`, or the build fails.
2. **Refuse to publish without the release keystore** — a tag build with no `ANDROID_KEYSTORE_BASE64` secret fails rather than shipping a debug-signed APK.
3. **Quality gates**: `./gradlew detekt unitTests`.
4. **Build**: `:app:assembleRelease` (per-ABI + universal) and `:node:distTar`.
5. **Package** into `dist/` as `vMessenger-<version>-<abi>.apk`, plus `vmessenger-node-<version>.tar.gz` and the R8 `mapping.txt`.
6. **Checksums**: `sha256sum *.apk *.tar.gz > SHA256SUMS.txt`, plus a per-APK `.sha256` file.
7. **Signature verification** with `apksigner` from the newest installed build-tools:
   - `apksigner verify --print-certs` for each APK into `dist/SIGNING.txt`;
   - the build **fails** if any certificate is `CN=Android Debug`;
   - the build **fails** unless every APK has exactly one `Signer #1` digest and all of them are identical — Android refuses to install one ABI's APK over another's if they are signed by different certificates.
8. **Publish** as GitHub Release assets (not Actions artifacts, which are quota-limited): the APKs, their `.sha256` files, the node tarball, `SHA256SUMS.txt`, `SIGNING.txt` and the mapping file. A tag containing `-rc`, `-beta` or `-alpha` is marked pre-release and does not become `latest`.

### Verifying a downloaded release by hand

```bash
sha256sum -c SHA256SUMS.txt
"$ANDROID_SDK_ROOT"/build-tools/*/apksigner verify --print-certs vMessenger-<version>-arm64-v8a.apk
```

The printed `Signer #1 certificate SHA-256 digest` must match the one in `SIGNING.txt` for that release and must not be the Android debug certificate.
