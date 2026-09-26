# AGENTS.md

How to work in this repository, for people and for coding agents alike. The README says what vMessenger
is; `docs/` says how it works. This file says how to change it without breaking it.

## What this is

vMessenger is a decentralized, end-to-end encrypted Android messenger (Kotlin, Clean Architecture + MVVM,
Hilt, Jetpack Compose, Room over SQLCipher) with a JVM reference node (`:node`, Ktor) that anyone can run.
The app can also set a server up as a node over SSH ("New node", `:feature:provision`). Persian is the
default language and English the second; both are first-class.

## Environment

- **JDK:** Gradle runs on JDK 17. The maintainer's checkout keeps one at `.jdk/jdk-17` (git-ignored, so
  not in a fresh clone; any JDK 17 will do); on macOS it is `.jdk/jdk-17/Contents/Home`. Gradle
  provisions the 21 toolchain it compiles with (foojay). JVM libraries emit Java 17 bytecode;
  `:node:verifyBytecodeLevel` refuses anything newer.
- **Android SDK:** kept at `.android-sdk` in the maintainer's checkout, also git-ignored (SDK 35, Build
  Tools 35). Set `ANDROID_SDK_ROOT` to it, or to your own SDK.
- **Docker** (optional): only for the installer harness (`scripts/provision-test/`).

```bash
export JAVA_HOME=$PWD/.jdk/jdk-17/Contents/Home ANDROID_SDK_ROOT=$PWD/.android-sdk
```

## Commands

| What | Command |
|---|---|
| The gate — run before every commit | `./gradlew detekt unitTests assembleDebug :node:installDist` |
| One module's tests | `./gradlew :data:testDebugUnitTest` (Android) or `./gradlew :core:common:test` (JVM) |
| One test class | `./gradlew :network:messaging:testDebugUnitTest --tests '*RelayListenerTest'` |
| Fix formatting in one module | `./gradlew :feature:chat:detekt --auto-correct` — never project-wide: it rewrites unrelated files |
| Release build (local, debug-signed without the keystore) | `./gradlew :app:assembleRelease :node:distTar` |
| The node, locally | `./gradlew :node:run --args="--tcp"` then `./scripts/emulator-connect.sh` |
| The installer against throwaway servers | `scripts/provision-test/run.sh test` (see docs/Testing.md §2.1) |
| The New node engine against a harness server, over real SSH | `scripts/provision-test/run.sh test --scenario engine` |
| `setup-node.sh` lint | `shellcheck -S style scripts/*.sh scripts/provision-test/*.sh` |

`unitTests` is the aggregate: a plain `testDebugUnitTest` skips the JVM modules (`:core:common`, `:domain`,
`:core:ssh`, `:core:nodesetup`, `:node`). `assembleDebug` compiles modules that `unitTests` doesn't, so
the gate needs both.

## Emulators

Two AVDs, `Pixel_8` (emulator-5554) and `TestB` (emulator-5556), arm64 system images (created as in
docs/Testing.md §3.2). Boot headless:

```bash
.android-sdk/emulator/emulator -avd Pixel_8 -port 5554 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot
```

Check a UI change in Persian (RTL) **and** English (LTR). Screens with secrets set `FLAG_SECURE`, so
screenshots of them come out black; read the view tree with `uiautomator dump` instead. The two-device
procedure is docs/Testing.md §3; New node against a harness server is §2.2.

## Module map

```
app/                  application, navigation (VmRoute, graphs), MainActivity, onboarding
domain/               pure Kotlin: models, repository interfaces, use cases
data/                 repository implementations, network coordinators, wipe, backup, NodeSetupController
feature/              identity, lock (app lock), pairing, contacts, chat, map, settings, debug, about,
                      provision (New node)
network/              discovery, dht, bootstrap, transport, messaging
core/common           shared types, NodeUrl / SpkiPin / PinnedTls, AppResult, logging
core/crypto, proto    libsodium engine; protobuf wire formats
core/database         Room + SQLCipher, schemas in core/database/schemas/
core/designsystem     the in-house design system (Compose Foundation), strings for shared components
core/ssh              SSH client (sshj) behind a small interface — JVM
core/nodesetup        the New node engine and its contract with setup-node.sh — JVM
node/                 the reference DHT + relay node — JVM
scripts/setup-node.sh the one installer: by hand, and in machine mode for the app (docs/Deployment.md §8)
```

## Architecture rules

- `domain` depends on `core:common` only. No Android.
- A `feature:*` module goes through `domain` use cases. The exceptions are declared in its
  `build.gradle.kts` and documented in docs/FolderStructure.md §8; don't add one without a reason written
  there.
- `data` alone wires repositories to `network`, `database` and `datastore`.
- One screen = one `*Route` composable + one `@HiltViewModel` exposing `StateFlow`s; events go in, state
  comes out. `SavedStateHandle` holds route arguments only.
- JVM logic that doesn't need Android goes in a JVM module, where it is tested without Robolectric.

## UI rules

- **Never add `androidx.compose.material3`** (or `androidx.compose.material:material`; the Material
  icon set, `material-icons-extended`, is the only Material dependency). The design system is our
  own, on Compose Foundation: use `core:designsystem` components (`VMessengerScaffold`, `VmButton`,
  `VmTextField`, `VmSecretField`, `VmNotice`, `VmStepList`, `ConfirmDialog`, …) and add one there when a
  screen needs something new. Don't copy code from Element or other AGPL projects.
- Every user-visible string is a resource, **in Persian (`values/`) and English (`values-en/`) in the same
  change**. Escape apostrophes in XML (`\'`). A ViewModel emits a `UiMessage` or a resource id, never a
  sentence.
- A node is «گره» in Persian copy.
- Technical text — IP addresses, ports, host names, URLs, fingerprints, logs — is laid out left to right in
  both languages and keeps ASCII digits (`VmCodeBlock`, or an LTR `CompositionLocalProvider`). Everything
  else uses `start`/`end`, never `left`/`right`.
- A button that tracks a press must not leave composition mid-gesture.

## Static analysis

detekt with `maxIssues: 0`: lines ≤ 120 columns, functions ≤ 60 lines, cyclomatic complexity ≤ 15, ≤ 11
functions per file, ≤ 6 parameters (7 for constructors), ≤ 2 `return`s and ≤ 2 `throw`s per function, no
spread operator, ktlint import order (`java`, `javax`, `kotlin` last). Fix the code rather than suppress;
when a suppression is right, put it on the declaration with a comment saying why
(`@Suppress("LongParameterList") // …`). Some modules carry a `detekt-baseline.xml` for old findings; a
baselined signature that you change must be updated, not left to fail.

## Database changes

1. Bump `DatabaseSchema.VERSION`, add `MIGRATION_n_n+1` and register it.
2. Build once so Room exports `core/database/schemas/…/<n+1>.json`; commit it.
3. Extend `MigrationTest` and set `CURRENT` in `V1DatabaseUpgradeTest`.
4. `@Insert(onConflict = REPLACE)` on a parent table deletes its children through the foreign-key
   cascade: use an upsert or an update for rows that have children.
5. Decide whether the table belongs in backups, and whether secure wipe must clear it.
6. Update docs/Database.md (entity, migration chain) and the CHANGELOG.

## Security rules

- No secret — keys, passphrases, SSH passwords or keys, the database key — in a log, `SavedStateHandle`,
  `rememberSaveable`, DataStore, the database or a backup, unless it is wrapped by the Android Keystore.
  The one deliberate exception is the identity key pair, which the passphrase-encrypted backup carries.
  Hold them as arrays, and wipe them when done.
- New persistent state must be covered by secure wipe (`SecureWipeCoordinator`, docs/Security.md §9).
- Only the person changes a node's certificate pin. Peers, the DHT and imports never add a second pin for
  a stored location.
- Over SSH, the host key is confirmed before any credential is sent.
- Changes to crypto, networking or storage come with the matching update in `docs/`.

## Operational safety

- **Never** SSH into, deploy to or restart the production node (`relay.vmessenger.ir`) or any real server
  without the maintainer's explicit permission for that action. Test against the Docker harness.
- Never push tags, publish releases or touch signing secrets (`ANDROID_KEYSTORE_*`, `ANDROID_KEY_*`)
  without the maintainer's go-ahead. Never fake a signature or a signing step.
- Throwaway harness credentials live in `scripts/provision-test/out/` and are never committed.

## Docs, changelog, commits

- Every change updates the docs it makes wrong, and adds a line to `CHANGELOG.md` under `[Unreleased]`
  (Added / Changed / Fixed / Removed / Security).
- Commits: an imperative subject line, then a body saying what changed and why. The gate passes on every
  commit.
