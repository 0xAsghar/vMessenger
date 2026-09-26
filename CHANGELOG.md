# Changelog

All notable changes to vMessenger are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file starts at 1.0. Earlier releases exist only as the `v0.1.0` … `v0.5.1` tags and their
GitHub Releases; they were never tracked here and are not reconstructed.

Two version numbers move independently of this file and are stated where they matter: the **wire
protocol major** (currently 2, [docs/Protocol.md](docs/Protocol.md)) and the **database schema
version** (currently 25, [docs/Database.md](docs/Database.md)).

## [Unreleased]

## [2.0.1] - 2026-09-26

Fixes and small additions to 2.0.0. No database or wire changes: schema stays 25, protocol major 2.

### Added

- **Location history on a contact's page.** Every place a contact shared with you in the last
  24 hours, newest first (a stay is one entry, timed when they arrived), with the route drawn on the map — also after they stop sharing, where the
  map shows where they last were. Before, the page showed a position only while a share was open.
- **Call from a contact's page and from the contacts list.** A contact's page has *Chat* and *Call*
  side by side, and each contact in the Contacts tab has chat and call icons for one-tap starts.
- **First-run permissions are asked where they're needed**: background activity (the battery-optimisation
  exemption) as the node step ends, and location right after the ID is created. Refusing either still
  moves on.

### Changed

- **Location sharing turns on without choosing contacts first.** With nobody ticked in "who may see
  me", switching sharing on shares with every approved contact and ticks them, so the list shows who
  sees you. Contacts can still be unticked one by one.
- **A timed message shows its timer only to its sender.** The person receiving it no longer sees the
  timer icon on the bubble; the message still disappears on time for both.
- **No standing banner for message review.** A group with review of edited and deleted messages on no
  longer carries the "admins can review…" banner on its screen; switching review on or off is still
  written into the group's history. The app runs on organization-managed phones whose staff are told
  how it works at install (Security L19, §14).

### Fixed

- **The location-request and location-sharing notifications could show in English** while the app was
  in Persian. They, and the message notification, now use the app's language rather than the
  device's, as the call and network notifications already did.
- **CI's shellcheck flagged `setup-node.sh`** (SC2015, an `A && B || C` when locating the checkout); it is
  rewritten with the same behaviour.

## [2.0.0] - 2026-09-25

The first stable V2 release: everything in 2.0.0-beta.1 below, plus setting up your own node from the
app. **Database schema 24 → 25** (20 → 25 from 1.1.2); migrations included, no action needed. **Wire
protocol stays major 2**, so phones on 1.1.2 still exchange messages with 2.0.0 — but see Deprecated.

### Deprecated

- **1.1.2 is deprecated.** It gets no further fixes; install 2.0.0 over it (the upgrade keeps every
  message, contact and setting — Testing §1, the V1 regression suite). A 2.0.0 phone keeps talking to a
  1.1.2 phone for now, but 1.1.2 cannot place or answer calls, reach pinned nodes (Security L20), or see
  albums, message timers and group roles.

### Removed

- **The in-app updater.** The app no longer contacts GitHub to look for, download or install a new
  build; install a newer APK by hand over the old one (same signing certificate, so data is kept).
  The Update screen, the Settings badge, the Home banner, the Debug base-URL override and the
  `REQUEST_INSTALL_PACKAGES` permission are gone. On the first start after upgrading, the updater's
  leftovers — its preferences file and any downloaded APKs in the cache — are deleted, and a secure
  wipe deletes them too.

### Added

- **Pinned node certificates.** A node address may end in `#pin-sha256=<key>[,…]` (up to four keys): the
  app then trusts exactly that certificate key — no CA, no name or date check — which is how a node on a bare
  IP address gets a certificate a phone can verify. The pin travels inside the address, so `vmnode:` links,
  signed endpoint records and the relay a call falls back to all carry it, and signatures over the address
  cover it; it is never sent on the wire. `NodeUrl`, `SpkiPin` and `PinnedTls` in `core:common`; the grammar is
  Protocol §19, the threat model Security §17. Apps older than this fail closed on such an address (L20).
- **Machine mode for `setup-node.sh`** (`--from-app`), the protocol the app's node setup will speak
  over SSH: an uploaded bundle checked against `SHA256SUMS`, no downloads but apt and certificates, a
  detached run under systemd that survives the connection, `--follow` that resumes from any byte,
  `##vm` progress markers and a `result.json` (Deployment §8).
- **The installer checks the server first and fixes what commonly goes wrong** (Deployment §8.5): the OS
  release and architecture, memory, disk and clock; it waits out cloud-init and automatic updates, finishes an
  interrupted dpkg, leaves broken third-party repositories out, moves to a mirror that answers (and is not
  stale) when the server's does not, falls back to the archives for end-of-life releases, retries on a lossy
  link, adds swap on small servers, and chooses a JRE 17+ that apt offers. It never edits the server's apt
  sources. Decisions — an untested release, setting the clock — are asked (`--allow`), not taken.
- **The installer gives a node pinned addresses when no CA vouches for it** (Deployment §8.6): a kept EC
  P-256 key, `ip-pinned` / `domain-ca` / `domain-pinned` modes with automatic fallback when Let's Encrypt is
  not possible, `--public-host`, `--public-port`, `--no-http`, port-owner checks (apache2 stopped only when
  allowed), transactional nginx changes, an atomic install with rollback, and health checked through nginx
  with the pin. `result.json` carries the pin, the certificate and the URLs a client should use.
- **Securing the server** (`--secure`, Deployment §8.7): fail2ban for SSH, automatic security updates, time
  sync; and `--key-only-ssh`, armed with a rollback timer and kept only when a fresh key-only login confirms
  it, so a mistake cannot lock the owner out. `--uninstall [--purge]` removes what the installer added.
- **The APK carries the node installer** (`assets/node-installer/`, built by `:app:bundleNodeInstaller`):
  the script, templates, the node tarball, a manifest and checksums, so setting up a node fetches nothing
  from GitHub. This adds about 12 MB to each APK.
- **`:core:ssh`**, the SSH client "New node" uses (sshj): the host key is learned before any login, a login goes
  only to the confirmed key, and passwords and keys are wipeable arrays that never print.
- **The app can run sshj on Android**: the platform's cut-down "BC" provider is replaced by the full
  BouncyCastle, at the same position, the first time a node is set up (`SshCryptoProvider`); R8 keep rules
  ship inside `:core:ssh`; sshj's logging goes to slf4j's no-op. Release APKs grow by about 3 MB for this
  (arm64: 41.0 MB with the bundled node installer).
- **`:core:nodesetup`, the New node engine**: host key before login, privilege, only-what-changed upload,
  preflight decisions, the detached install followed through dropped connections, key-only SSH confirmed by a
  fresh key login, `result.json` checked (the pin must be the certificate's key). A contract test keeps its
  issue codes and step ids in step with the installer.
- **Design-system pieces for setting up a node**: `VmStepList` (a process as steps, announced as they
  change), `VmSecretField` (`BasicSecureTextField`, never saved with the screen), `VmNotice`, `VmCodeBlock`
  (monospace, left to right, copyable), and `RequireSecureWindow` / `KeepScreenOn` / `ExcludeFromAutofill`
  — a screen that asks for a server password forces `FLAG_SECURE` whatever the screen-security switch says.
- **Database schema 24 → 25: `managed_node`**, the servers this device set up ("Your servers"): host, SSH
  port and user, the confirmed host key, the node's addresses, version and state — never a password or key.
  One row per server; no foreign key; in the encrypted database, not in backups, erased by a wipe. With it,
  `ManagedNode`, `ManagedNodeRepository` and the use cases that complete a setup (the node's addresses join
  the app's nodes through the usual checks) or forget a server, and `SemVer` back in `core:common`.
- **New node** (`:feature:provision`): the person gives a server's SSH login (password, or a key file with
  its passphrase) and the app turns it into a node and adds it — with or without a domain, always over TLS,
  with "Secure this server" on by default and key-only SSH offered when logging in with a key. The server's
  fingerprint is shown and confirmed before anything is sent; a sudo password or a consent the installer needs
  is asked as the run reaches it; the steps, issues and log are shown live; a dropped connection is picked up
  where it left off. The phone checks the node with its pin before it is added, and a node answering with a
  different certificate is not added. Reached from Settings → Nodes ("Set up a new server") and from the
  first-run node question ("Create a node"), which records the node as the person's own.
- **"Your servers"** on the Nodes screen: the servers this device set up, with "Update" when the app carries a
  newer node (the SSH login is asked again) and "Forget".
- **`AGENTS.md`**: how to work in the repository — environment, the gate, emulators, module map, the
  architecture, UI, detekt, database and security rules, and what must never be done without the
  maintainer (touching production servers, tags, signing).
- **Debug builds show a *Public port* field in New node**, for test servers whose port 443 is mapped
  elsewhere (the Docker harness; Testing §2.2).
- **Ubuntu 26.04 and Debian 13** are supported, after installing end to end in the harness.
- **A Docker harness for the installer** (`scripts/provision-test/`): throwaway systemd + sshd
  containers on `127.0.0.1`, driven over real SSH, with scenarios for the happy path, resuming a
  dropped follow, a busy server, a corrupt bundle and a user without sudo (Testing §2.1).

### Changed

- **The node runs on Java 17 or newer**, so Debian 12's own `openjdk-17-jre-headless` is enough. The
  JVM modules still build with the 21 toolchain but emit Java 17 bytecode, and `:node:verifyBytecodeLevel`
  refuses to package any class, ours or a dependency's, that a Java 17 JRE could not load. The node's
  tests run on Java 17.
- **The node verifies Ed25519 with the JDK instead of libsodium.** lazysodium-java 5.2 is built for
  Java 21 and loads a native library through JNA. `Ed25519Verifier` adds libsodium's stricter checks
  (canonical `S` and key, no small-order key or `R`), so the node still refuses what the app refuses.
  With no native code left, the node tarball is 12.8 MB instead of 16.6 MB. It now carries a
  `VERSION` file and no Windows `.bat` launcher.
- **Every WebSocket to a node opens through one function** (`WebSocketFrameClient.openWebSocket`), which
  picks pinned or CA trust and the relay backend; the copies in the relay transport and the listener are gone.
  Variants share one base client, the shared dispatcher is uncapped (an open WebSocket holds its call for its
  whole life), and the variant cache is a bounded LRU because DHT peers supply addresses.
- **One row per node location, and only the person changes its pin.** Adding a node at a stored location
  with a new key replaces the row; peers, the DHT and signed records cannot add a second pin for it; a
  restored backup keeps the pin that is here. The Nodes screen shows pinned nodes with a badge and the
  address without the pin; the activity log records it that way too.
- **A malformed pin is an error** (`MALFORMED_PIN`, «کلید سنجاق‌شده … معتبر نیست»), not ignored, and a stored
  node that no longer passes the address policy is skipped when choosing a relay or bootstrap node.
- **"Pinned" relay IPs are now "sticky"** (`RelayDns.stick`, `clearStickyIps`): "pin" means a certificate key.
- **The node's unit sets `JAVA_HOME`** to the JRE the installer chose, and sizes the heap to the server (a
  quarter of its memory, 128–768 MB, instead of a fixed 512 MB).
- **The Nodes screen's guide no longer shows the GitHub one-liner**: it points at "Set up a new server",
  and the app downloads nothing from GitHub. The first-run "Create a node" step opens New node instead of
  asking for the address of a node started elsewhere.
- **Docs**: README (New node, pinned addresses, limitations, schema 25, the in-house design system),
  Deployment §0 (setting a node up from the app; a domain is optional), Security §18 (setting up a node over
  SSH) and L21–L22, Architecture §10.6 (the provisioning data flow), UI §5.9 and the Persian-and-English
  rules, FolderStructure (`feature:provision`), Testing §2.2 (New node against a harness server). CI runs
  shellcheck on the harness too.
- **`setup-node.sh` leaves other nginx sites alone** (it used to delete `sites-enabled/default`) and no
  longer asks nginx for `http2`, which is deprecated in the `listen` form. Every fatal error names an
  issue code, and the exit status gives its class (Deployment §3.4).

### Fixed

- **The published endpoint record never followed the relay listener to a new relay**, so after adding a
  relay — or when the chosen one failed and the listener moved on — peers dialled a relay this device was no
  longer on. The record is now re-published whenever the listener lands elsewhere, and a change to the relay
  list reconnects the listener at once.
- **A broken user-added relay could hold the listener forever**: once the default relay also had three old
  failures, priority alone decided and the user's relay always won. Among failing relays, the one that failed
  longest ago is tried first now.
- The Add node dialog stayed open after a node was added.
- **Sticky relay IPs never took effect.** They were recorded from an OkHttp `EventListener`, which OkHttp does
  not give WebSocket calls, so every relay socket fell back to resolver order. The backend is now recorded
  when a socket dialled to it opens.
- The DHT client read `WSS://` (any upper case) as a raw TCP address; schemes are case-insensitive now, as the
  address policy already treated them.
- `setup-node.sh` exited 1 on every error, and could exit 1 after a successful install: its cleanup
  trap ended on a failed test, which `set -e` turned into the script's exit status.
- `setup-node.sh --build` and `--skip-build` looked for the node in `node/build/install/node`; Gradle
  writes it to `node/build/install/vmessenger-node`.
- The installer's closing hint said «نودهای شبکه»; the app calls them «گره‌های شبکه».

## [2.0.0-beta.1] - 2026-09-22

The V2 release: voice calls, English alongside Persian, and a batch of messaging, location and
group-administration features.

**Database schema 20 → 24.** Migrations included; no action needed. **Wire protocol stays major 2**
— every new field and message is additive, so a 1.1.2 peer ignores what it does not recognise
instead of failing. A 1.1.2 peer cannot place or answer a call, and will not see an album as a grid
or honour a message timer.

### Added

- **Voice calls.** One-to-one, end-to-end encrypted, with a media key derived per call from a fresh
  X25519 exchange carried inside the already-authenticated signalling — so a later compromise of
  the long-term keys does not recover a past call. Ringing takes the screen over the lock screen and
  can be answered from the notification. Opus at 48 kHz in 20 ms frames, with concealment for lost
  packets, mute, and a speaker/earpiece choice.

  Audio takes a direct connection when the two phones can reach each other and a relay circuit when
  they cannot — tried at once, so a call connects as fast as the better of the two allows. The
  circuit is named from the call's own key, so nobody else can route one into it, and the relay
  carries only sealed frames. A call whose connection drops — a network change, a stalled relay —
  says so and reconnects on its own, for up to 30 seconds, without the other person redialling.

  The microphone is opened only by answering, and only under a foreground service, so the system
  privacy indicator and the notification shade both say so for as long as it is true.

- **English.** The app can now present itself in English as well as Persian, with the calendar,
  digits, dates, file sizes and layout direction all following the choice. Persian remains the
  default and is unchanged.

- **Self-destructing messages.** A timer set on a conversation stamps each message with an absolute
  deadline, and every device that holds a copy deletes it when that passes — there is no server to
  withhold anything. The timer is either a duration (1 hour, 24 hours, 7 days, counted from each
  message's sending) or a date and time picked in the app's calendar — Jalali in Persian, Gregorian
  in English — at which every message sent until then disappears together. It applies to photos,
  albums, files and voice messages as well as text, and a message is erased at its deadline while
  the app is running, not at the next quarter-hour sweep. That includes a sealed store-and-forward
  copy parked on another device for a recipient who is offline: it is held until the message's
  deadline or the mailbox's own day, whichever comes first, and a message already past its deadline
  is never parked at all. Like delete-for-everyone, it is best-effort against a recipient who does
  not want to comply, and the app says so where it is offered.

- **Multi-image albums.** Photos picked together are sent as one album, in order, with per-image
  progress; a failure affects only the image that failed. An album is drawn as one grid in one
  bubble — rows of two or three, with one time and one set of ticks — and each photo in it still
  opens, replies and long-presses on its own.

- **Share into a conversation.** vMessenger appears in other apps' share sheets, for text and for
  files, and shared content lands in the conversation you pick. Messages can be shared out the same
  way.

- **A standalone map.** The map tab now works without selecting anyone: your own position, every
  approved contact with both directions of sharing status, and the distance to each. A contact's
  shared route is drawn for the session they shared it in — the path they actually sent, not an
  inferred history.

- **Asking someone to share their location.** For a contact whose safety number you have verified,
  the app can send a request that raises a prompt on their phone. It is a request: nothing starts on
  their device, they can ignore it, and both devices enforce the verification requirement.

- **Choosing a node at first run.** A new install is asked which node to use before an identity is
  created — the built-in test nodes, an address of your own, or none for now — and skipping really
  means none, rather than quietly seeding the defaults on the next connection. Existing installs are
  unaffected.

- **Group admins, and review of edited or deleted messages.** A group's creator can name admins and,
  separately, switch on retention of what a message said before it was edited or withdrawn.
  **It is off by default and never applies to a private conversation.** When it is on, every member
  of that group sees a banner saying so and a line in the conversation recording the change, because
  a group where this is on is a group its members should be able to leave over. Switching it off
  erases what was kept. Captures are bounded to ninety days, are not backed up, and are erased with
  the message itself — so a message that self-destructs takes its captured text with it.

- **An activity log.** Your own record of what you did to this app — unlocking, adding a node,
  granting a permission, starting a location share, placing a call — readable in Settings and
  exportable as JSON, CSV or text. It deliberately does not record who you communicated with: a
  call appears as a call, not as a call with someone.

- **Groups of up to 100.** Raised from 32.

### Changed

- **User IDs now start with `vm-`** instead of `vm2-`, and both forms are accepted anywhere an ID is
  entered. Your own ID and your contacts' switch to `vm-` on their own. **Someone still on 1.1.2
  cannot type or paste a `vm-` ID** — adding each other by QR code still works in both directions,
  and so does everything after the contact is added.

- **The notification permission is explained before it is requested,** once, in the app's own words,
  and dismissing the explanation counts as an answer instead of asking again on the next launch.

- **New shared UI components** (buttons, fields, switches, chips, the navigation bar) with the app's
  own tokens, used by everything added in this release.

### Fixed

- **Removing an account left its background work scheduled.** The keep-alive job exists to restart
  the network, so a wipe that stopped the network but left the job enqueued had it brought back.
  Cancelling that work is now the wipe's first step, before the network is stopped, and the order is
  pinned by a test.

- **A relay circuit closed by the other end went unnoticed.** The relay's close was never answered,
  so the circuit sat half-open and this side kept reading a connection that was gone until a write
  happened to fail. It is answered now, and a dial the relay closes before it is ready fails at once
  instead of at the dial timeout.

## [1.1.2] - 2026-09-17

Ten bugs reported against 1.1.1, each reproduced on two emulators before it was fixed and checked
there again after, plus one defect found on the way.

**Database schema 19 → 20.** Migration included; no action needed.

### Fixed

- **Scanning a contact's QR code left a blank white screen.** The scanner waited for the contact
  request to go out before closing — up to a minute against an offline peer — with the camera
  already off, and a result landing during its exit animation could pop the contacts tab too and
  empty the app. The request now goes out in the background, the scanner closes as soon as the
  contact is saved, and no screen can close anything but itself.
- **A contact added while they were offline never received the request.** Four causes: a QR add
  was approved on the spot instead of waiting for them; node-exchange traffic after any handshake
  counted as the peer having answered, which stopped the retries; deleting someone while they were
  offline queued a "you were removed" that re-adding them did not cancel, and the two raced when
  they came back; and retries wrote into sessions whose peer had silently gone. Contacts you add
  now show «در انتظار تأیید» until the other side accepts, retries dial fresh every time, the wait
  between them is at most five minutes (hourly after that, for a week), and a re-add withdraws the
  pending removal. *A contact QR-added while offline on 1.1.1 may still be stuck as approved on
  your side only: delete and add them again.*
- **Swiping down on a photo turned the app black.** The viewer closed itself on every frame of the
  swipe, popping screen after screen. It now closes once, on release, swiping up as well as down;
  a short drag springs back.
- **«اشتراک موقعیت فعال است» stayed after sharing was turned off.** The location notification
  shared its id with the network service's, which kept showing it. They are separate now.
- **Several photos could not be sent at once.** The photo, video and file pickers now take up to
  ten items per pick, sent in the order chosen.
- **A sharing contact's distance showed twice, and replaced when you last heard from them.** The
  row now reads distance and last activity together, under the name.
- **Composer buttons sat low beside a one-line message.** They are centred on one line and move to
  the bottom as the message grows.
- **Queued removals to two contacts at once could reach only the first**, and be dropped as
  delivered for the second.

### Added

- **A contact's screen shows where they are on a map** while they share their location with you.

### Changed

- **Message info** no longer has a «نوشته شد» row. An edited message shows «ویرایش شد» and a
  message deleted for everyone shows «حذف شد», each with its date and time. Messages deleted
  before this version show no deletion time, because none was kept.
- **App lock settings** no longer carry paragraphs under the switches; each explanation appears in
  the dialog that turning the switch on opens.

## [1.1.1] - 2026-09-15

The first release after 1.0.1 was used in anger. Eleven reported interface bugs, four reported
technical ones, three new features, and an audit of what a peer-to-peer app does when it cannot
reach the other side — which turned up about twenty more defects nobody had reported yet.

*1.1.0 was prepared and never published: its build was pulled when testing the app lock on a real
device turned up a defect that would have destroyed data. Everything below reaches users here.*

**Database schema 18 → 19.** Migration included; no action needed.

### Security

- **App lock.** A PIN gate over the app, off until you set one. The default mode protects the
  screen and says so in as many words — messages still arrive, storage is unchanged, and anyone
  holding your unlocked phone reads everything regardless. **Strict mode** is the one that changes
  anything: the database passphrase is re-wrapped under a second Keystore key that requires
  authentication, and the ordinary copy is deleted, so the database key cannot be produced at all
  without a device authentication. That is not the same as the retry limit being hardware-enforced
  — the PIN is still checked in software, with this app's own rate limit on top; what the hardware
  gates is the key. Device credential is accepted alongside biometrics, which is what keeps the key
  alive across a new fingerprint enrolment; removing the device screen lock, or moving the app's
  data to another device, does destroy it, and there is no way back except a backup file, which the
  confirmation says before you turn it on. Background delivery really does stop while strict mode
  is locked: the network stack goes down with the lock and comes back on the unlock, because
  closing the database alone stops nothing — Room is already holding the key.
- **The lock arms while the app is away, not when you come back**, so the promise holds for a phone
  sitting in a pocket rather than only from the moment you look at it. Notifications go
  content-free while it is engaged, and a dialog or sheet left open no longer draws over it.
- **Wrong PINs are rate-limited.** Four are free, then the wait doubles from five seconds and caps
  at five minutes. It is kept on disk and judged by two clocks, so neither force-stopping the app
  nor winding the device date forward shortens it; across a reboot, which resets the monotonic
  clock, the wall clock decides alone. The wipe-after-ten-failures trigger stays opt-in and off by
  default, and counts consecutive failures — a correct PIN clears the count.
- **The secure wipe now clears the app lock's own store and its Keystore alias.** It cleared
  neither. The PIN verifier is offline-crackable, and a surviving strict-mode blob left the app
  refusing to open a database that no longer existed — a crash on every start after a wipe.
- **Screen security is forced on while the lock is showing**, whatever the setting says, so the
  recents thumbnail cannot leak the last unlocked screen.

### Added

- **Swipe a conversation left-to-right to go back.** Left to right because the app is
  right-to-left: content advances leftward, so returning is the reverse — and the opposite
  direction from the swipe that replies to a message, which is why the two do not collide. An
  open reply is cleared first, exactly as pressing back does.

- **Edit and delete for everyone.** Both are additive to wire protocol 2, so a 1.0.x peer ignores
  them rather than breaking. Delete renders a tombstone rather than removing the row, and is worded
  as a request: a peer can ignore it and nothing here can prove otherwise.
- **Your display name now reaches your contacts** instead of only ever being local. A name you
  typed for someone else is never overwritten by theirs.
- **Store-and-forward actually delivers.** The third-party mailbox existed on the wire with no
  caller: a message to an offline peer simply died after 24 hours. It is now offered to reachable
  approved contacts and collected on connect. The trade is recorded in
  [docs/Security.md](docs/Security.md): parked blobs have no forward secrecy, and a host learns
  that someone holds a message for a routing key.
- **Last heard from** on the contact row — worded that way because the column tracks the last
  inbound frame, receipts included, not a visit.
- **Message information** for any message, not only outgoing group ones, showing only what is
  actually recorded.

### Fixed

- **Location sharing did not stop the GPS.** Six separate causes, including a third location
  registration that ran whenever the contacts tab was open, regardless of the toggle. Verified with
  `dumpsys location`: no registration on the contacts tab, none on the map with sharing off, one
  while "my location" is held, and none twenty seconds after leaving.
- **Latin text rendered with its punctuation on the wrong side.** No text style in the app set a
  direction, and an unspecified direction under an RTL layout resolves to hard RTL rather than
  first-strong. Names interpolated into Persian sentences are isolated so a Latin name cannot flip
  the sentence around it.
- **The composer.** Voice and attachment swapped sides, the send glyph is an up arrow, the card
  floats, and the slide-to-cancel axis follows the mic to its new side.
- **A reply icon sat beside every message** — it was the swipe background, always drawn and merely
  revealed. **Tapping reply could expand the composer to fill the screen.**
- **"Extended map" behaved like "my location"**, because fitting all markers downgraded itself to
  follow-me whenever there were none, which is the ordinary case.
- Two back arrows on the new-chat screen; a dead text button under the add-contact button; a
  duplicate identity row in settings; a QR scanner that neither closed nor said anything on a
  successful scan, and could fire the same request once per camera frame.
- The battery-optimisation row now disappears once granted instead of showing a finished task.
- Outgoing messages show when they were sent rather than when they were queued.

### Fixed — found by audit, not reported

- **A deleted contact was never told.** The revoke was sent once, best effort; if the peer was
  offline — the usual case — they kept you approved forever while their messages were dropped in
  silence. It is queued now, survives the contact row, and retries for a week.
- **Re-adding a revoked contact created a dead-end duplicate** that could never leave pending.
- **A clock jump forward failed every queued message**, because the retry window was measured
  against the wall clock.
- **Attachments claimed delivery on transport success**, so a receiver's disk-full or hash mismatch
  showed as sent.
- **Four states where the app went quiet and looked healthy** now say what is wrong, in Persian:
  a device clock outside the relay's window, the same identity active on another device, a group
  whose absent creator has frozen its membership, and notifications turned off — which stops
  background delivery, because the foreground-service notification is what keeps the stack alive.
- The contact-request retry budget is persisted, so a restart no longer re-dials at full speed.

### Changed

- One design system rather than eight screens that shipped together: shared list row, bottom sheet,
  input dialog and FAB, and the size and spacing tokens the settings, identity, debug, nodes, about
  and pairing screens had been bypassing. Some rows move by four device-independent pixels.
- The about screen states the protocol major, the database schema version and the nodes in use.
- Messages animate in and move rather than appearing; the delivery ticks cross-fade.

## [1.0.1] - 2026-09-12

### Fixed

- **The in-app updater could not verify any real release.** It read the signer digest out of a
  release's `SIGNING.txt` by matching `Signer #1 certificate SHA-256 digest:`, but the
  `apksigner` on the release runner prints `V2 Signer: certificate SHA-256 digest:`. No line
  matched, the digest came back empty, and the check — which fails closed — refused the
  download. Every genuine update would have been rejected as unsigned.

  The parser now matches on the stable part of the line (`certificate SHA-256 digest:`)
  whatever prefix the build-tools version uses, and an asset whose block carries two
  *different* digests is refused rather than resolved to one of them. The published 1.0.0
  `SIGNING.txt` is pinned verbatim in a test.

  Found by verifying the 1.0.0 release rather than trusting it: the format the updater was
  written against was an assumed one, and the local test server had been emitting it too.

  **1.0.0 users cannot update in-app to 1.0.1** — that is the bug. Download 1.0.1 from the
  releases page and install it over 1.0.0; the signing key is unchanged, so it upgrades in
  place with no data loss.

- The release workflow's signature gate compared a count of digest *lines* against the number
  of APKs. `apksigner` prints one line per signature scheme, so the counts could never match
  and a correctly signed 1.0.0 build failed the gate. It now collects the distinct digests per
  APK, and prints `SIGNING.txt` before it asserts anything — the old order hid the evidence in
  exactly the case where it was needed.

## [1.0.0] - 2026-09-12

The 1.0 line. It carries a breaking wire-protocol change: **a 0.x install must be uninstalled
before a 1.x build is installed**, and identity and contacts do not survive that. See
[README.md](README.md).

### Added

- **Group chat.** A group is created locally and delivered as N pairwise sends over the existing
  1:1 secure sessions, one outbox row each, so one unreachable member never holds the rest back.
  Membership is creator-authoritative and versioned: every structural change is a `GroupControl`
  from the creator at exactly `local + 1` carrying the full member list, and a device that sees a
  gap asks for a snapshot rather than guessing. The conversation screen names the group, renders
  membership changes as centred system lines, colours each sender's name on the first message of a
  run, and keeps a closed group's history while removing its composer.
- **Voice messages.** Hold the mic to record, slide toward the field to cancel, drag up to lock.
  Playback is a single shared player, so two voice messages cannot talk over each other, and it
  auto-advances through the unplayed messages that follow. "Unplayed" is persisted on the message
  rather than held in memory, and is set when playback starts. Duration and waveform travel in the
  transfer header, so a voice bubble has its shape before the audio lands.
- **Per-recipient delivery state.** A group message's single tick is an aggregate — it turns to
  delivered only once every member has it. Long-pressing one of your own group messages opens an
  info sheet with one row per member and the moment they received and read it.
- **A design system in `:core:designsystem`.** Spacing, size, elevation and shape tokens replace the
  dp literals each screen was inventing; the Material 3 light and dark schemes are complete,
  including the `surfaceContainer` ladder and inverse roles that components had been falling back
  to stock purple for; all fifteen type styles are defined in Vazirmatn. `VmDateFormat` and
  `VmTextFormat` render Jalali dates, Persian digits, file sizes, durations and relative times.
  Alongside them is a component catalogue — avatar, chat-list item, message bubble, reply quote,
  delivery ticks, date separator, composer, attachment sheet, empty state, skeleton list and the
  rest. Described in [docs/UI.md](docs/UI.md).
- **Type-safe navigation.** String routes are replaced by a `@Serializable VmRoute` hierarchy split
  into per-area graph files. Message notifications open the conversation they belong to instead of
  a bare launcher intent. The system splash is held until the start destination is known, so the
  first composed frame is the right screen.
- **Screens that had no UI behind an existing data path.** Contact delete, block and rename were
  reachable from no screen; a blocked contact was filtered out of the list and so could not be
  unblocked; the safety-number screen could not record an out-of-band comparison. All three now
  exist, along with contact detail as a real navigation destination, an in-app zoomable image
  viewer that decodes from the decrypted stream, a chat list with search and long-press selection,
  and a full-screen map.
- **Replies and drafts.** `replyToMessageId` was in the schema and in the wire proto but was never
  written or read; the outbox now sets it, the inbound collector stores it, and the quoted preview
  resolves in the same query. Composer text is persisted per conversation and cleared on send.
- **Identity and contacts backup.** A passphrase-protected `.vmb` bundle (Argon2id13 +
  XChaCha20-Poly1305) with restore inside a single database transaction.
- **An in-app updater** in `:core:update`: it queries GitHub Releases, selects the APK matching the
  device's ABI, and verifies the download against the release's published checksums and signer
  before handing it to the package installer. `REQUEST_INSTALL_PACKAGES` had been declared in the
  manifest for this since the permission pass; it is now used.
- **A canonical node installer.** `scripts/setup-node.sh` renders the nginx and systemd templates,
  installs a versioned `distTar`, obtains and renews a Let's Encrypt certificate, and health-checks
  the result. It is idempotent. See [docs/Deployment.md](docs/Deployment.md).
- **Release engineering.** The release workflow gates on `detekt unitTests`, refuses to publish
  without the release keystore, builds per-ABI plus a universal APK, verifies every signature with
  `apksigner`, and attaches checksums, signing metadata, the node tarball and the R8 mapping.

### Changed

- **Wire protocol major 2.** The handshake, the AEAD associated data, every signed transcript and
  the User Hash format (`vm1-` → `vm2-`) changed together. 0.x clients are rejected with a `CLOSE`
  frame and no compatibility shim exists in the app. The node accepts v1 and v2 proofs and records
  during the transition.
- **Database schema 18.** `conversation.contactId` became nullable and gained `groupId`, so one row
  shape serves a 1:1 thread and a group; `outbox` was re-keyed to
  `(messageId, recipientIdentityHash)`, which makes a 1:1 message the N = 1 case of a group send
  and keeps one delivery pipeline rather than two; `message_recipient` holds the per-member state
  the aggregate tick is derived from. Three tables were recreated rather than altered because
  SQLite cannot relax a `NOT NULL` column or change a primary key.
- **The conversation was hoisted out of the bottom-tab `NavHost`.** It had rendered the four-tab
  navigation bar underneath the message composer. The conversation now owns the whole window and
  the composer is the only owner of the bottom and IME insets.
- **Messages are windowed.** The conversation query had no `LIMIT`, so opening a chat loaded the
  entire thread and rebuilt the whole list on every new message. It now returns a newest-first
  window of 60 that grows on scroll, and far enough to reach a quoted message.
- **The chat list is one query.** `observeChatList()` is a single join over conversation, contact
  and last message, replacing the UI's combine of two flows.
- **Error text moved to the UI.** `lastError` is a stable code (`peer_protocol_outdated`,
  `peer_key_changed`, `endpoint_not_found`, …) rather than a Persian sentence built in the data
  layer.
- **Attachments go through the Photo Picker**, and images open in the in-app viewer rather than
  being handed to an arbitrary gallery app.
- **Endpoint records are re-announced.** `EndpointAnnouncer` republishes every TTL/2 (10 minutes)
  and on connectivity recovery. Records expired after 20 minutes and nothing refreshed them, so the
  production node showed `dhtRecords 0` against live clients.
- **Group controls are acknowledged like messages**, so the outbox stops retrying a change that has
  already landed.
- **Group control authority is the session.** `MessageEnvelope.sender_identity_hash` is
  peer-controlled and is verified nowhere; every authorization decision uses the identity the
  secure session authenticated. The comments that claimed otherwise now say what the code does.
- **The project is licensed GPL-3.0.**

### Fixed

- **A re-sent membership snapshot erased a group's whole chat.** `@Insert(REPLACE)` is
  `INSERT OR REPLACE`, which deletes the row before re-inserting it; on `chat_group` that cascaded
  through `conversation` to every message. `GroupDao` now separates `insert` (IGNORE) from
  `update`, and the cascade is pinned by a test on a real SQLite engine, where a list-backed fake
  could never have modelled it.
- **A recording never ended.** Replacing the composer with a recording bar took the mic button out
  of composition and with it the pointer loop holding the gesture, so the release was delivered to
  nothing. The mic now keeps its position in the same row while the rest of the composer swaps out.
- **A long press could not reach a voice bubble.** The waveform covers most of the bubble and its
  tap detector consumed the gesture, so reply, delete and info were unreachable on a voice message.
- **The first messages from a newly approved contact were dropped.** An inbound session resolved the
  contact id once, at handshake time, so a peer that dialled in as a stranger kept its provisional
  `stranger:<hash>` id after approval and every later frame was rejected by the inbound policy. The
  id is now re-resolved while it is provisional and the session rebinds.
- **Read receipts were never sent and unread counts only grew.** `markConversationRead` and
  `ActiveConversationTracker` existed but nothing outside the tests called either, so a
  notification also fired while its conversation was open on screen.
- **Avatars were blank on one side.** The identicon computed its mirrored column and never drew it.
- **A group sender's name never resolved**, because the join compared a full 64-character hash
  against a 32-character routing key.
- **A manual retry could re-queue a recipient already in flight**, resetting its backoff and receipt
  wait.
- **A draft outlived the contact it was addressed to**, and contact deletion materialised every
  message in a thread to collect attachment paths.
- **Contact detail lost its selection on rotation** and system back left the tab rather than closing
  the detail, because it was `remember` state behind an early return.
- **The cold start flashed a mismatched colour** and the splash slept 800 ms.
- **The tab scaffold applied a doubled top inset.**

### Removed

- `:core:storage` — it never contained anything but a placeholder marker object. Encrypted blob
  storage lives in `data` alongside the attachment pipeline.
- `:core:testing`, which held a single `MainDispatcherRule` that no module declared as a dependency.
- `feature:location`, replaced by the `core:map` / `feature:map` pair.
- Every `*ModuleMarker` placeholder, the `PairingRoute` "coming soon" screen and its string, and a
  duplicate `:core:common` declaration in `feature:pairing`.
- The dead `session` table, which was meant to go in migration 16. Sessions are connection-scoped
  and were never persisted.
- The hand-rolled thumbnail decoder with its English "MB"/"KB" formatting inside an RTL Persian UI,
  both in-app splash files, and two voice components scaffolded in `:core:designsystem` that the
  feature versions superseded.
- `scripts/cli-smoke-test.sh`, `docs/Bootstrap.md` (folded into [docs/DHT.md](docs/DHT.md) §4.1) and
  the pre-1.0 `vmessenger-relay` deploy artifacts.

### Security

- **The v2 handshake closes an active-MITM hole.** The responder's old signature covered only step
  one, so an attacker could swap the responder's DH keys and read everything. The responder now
  signs a canonical, length-prefixed transcript covering its own ephemeral, static and identity keys
  and its capabilities. Three DHs with low-order point rejection; the HKDF root is bound to the full
  transcript; the AEAD associated data binds version, frame type, sender key and counter.
- **The ratchet is bounded.** `MAX_SKIP` = 256 is enforced *before* any KDF, since a forged counter
  could otherwise burn roughly two billion HMACs. The skipped-key store is bounded, used keys are
  wiped, state commits only after a successful open, and wiped or closed sessions refuse further
  frames.
- **X25519 static keys are pinned.** A changed key is refused and recorded for re-verification
  rather than silently trusted.
- **Inbound authorization is a single policy.** Blocked contacts are rejected at the handshake, at
  the dispatcher and at every content kind. A stranger may complete a handshake but only
  `ContactRequest` and `ContactResponse` are accepted from one.
- **Group membership is authorization, not metadata.** `group_id` is peer-controlled, so being an
  approved contact is not enough to write into a group: it must exist locally, not be closed, and
  the sender must be an active member — checked before a message is persisted and before a single
  attachment chunk is staged. A receipt counts only if its sender is actually a recipient of the
  message.
- **Platform hardening.** Cleartext traffic is refused in release builds; `FLAG_SECURE` is applied
  before the first frame, so the first frame can never reach a screenshot or the recents thumbnail;
  notifications are `VISIBILITY_PRIVATE` with a content-free public version; Keystore blobs are
  versioned and bind their alias as AAD, using StrongBox where the device supports it; debug and log
  screens are unreachable in release unless developer mode is unlocked.
- **The secure wipe actually wipes.** It previously cleared identity and keys only, leaving
  messages, attachments, logs, the DataStore-held database passphrase and the Keystore key behind.
- **The reference node was hardened**: connection and record limits, listener-proof freshness and
  replay rejection, record expiry, and rejection counters on `/healthz?verbose=1`.

[Unreleased]: https://github.com/0xAsghar/vMessenger/compare/v2.0.1...HEAD
[2.0.1]: https://github.com/0xAsghar/vMessenger/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/0xAsghar/vMessenger/compare/v1.1.2...v2.0.0
[2.0.0-beta.1]: https://github.com/0xAsghar/vMessenger/compare/v1.1.2...v2.0.0-beta.1
[1.1.2]: https://github.com/0xAsghar/vMessenger/compare/v1.1.1...v1.1.2
