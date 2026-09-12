# vMessenger - Local Database

The on-device store: Room over SQLCipher, **schema version 17**.

Everything below is read off `core/database/src/main/kotlin/ir/vmessenger/core/database/` and the exported schema `core/database/schemas/ir.vmessenger.core.database.VMessengerDatabase/17.json`.

---

## 1. Storage philosophy

- The device is the only authority. There is no server copy of messages, contacts or keys, so the local database *is* the user's data.
- Nothing sensitive is written outside it except attachment files, which have their own container ([Security.md](Security.md) §7.2), and the Keystore-wrapped blobs in DataStore.
- `android:allowBackup="false"` — the database is never included in a cloud backup.
- Every enum is persisted as its Kotlin `name`, not an ordinal, so reordering an enum cannot silently reinterpret stored rows.

---

## 2. Engine and encryption

`core/database/.../di/DatabaseModule.kt`

```kotlin
Room.databaseBuilder(context, VMessengerDatabase::class.java, "vmessenger.db")
    .openHelperFactory(SupportOpenHelperFactory(passphrase))   // net.zetetic SQLCipher
    .addMigrations(MIGRATION_1_2 … MIGRATION_16_17)
    .build()
```

- File: `vmessenger.db` (`DatabaseModule.DATABASE_NAME`), also what the secure wipe deletes together with its `-wal` / `-shm` / `-journal` siblings.
- Passphrase: 32 random bytes, Keystore-wrapped, cached for the process lifetime by `DatabaseKeyProvider` under a mutex (see [Security.md](Security.md) §7.1).
- `exportSchema = true`; schemas land in `core/database/schemas/`.
- No `fallbackToDestructiveMigration` — every version step has an explicit migration.
- 17 entities, 17 DAOs.

**Note on the `session` table.** It is still declared (`SessionEntity`, `SessionDao`, table `session`) and still present in schema 17, but **nothing outside `:core:database` references it**: sessions are connection-scoped and never persisted ([Protocol.md](Protocol.md) §6). It is dead weight kept only because dropping a table requires a migration; treat it as unused.

---

## 3. Entity-relationship overview

```mermaid
erDiagram
  identity ||--o{ key_material : "wrapped private keys"
  contact ||--o| conversation : "1:1"
  contact ||--o| location_access : "grant"
  conversation ||--o{ message : "cascade"
  message ||--o| outbox : "queued send (no FK)"
  location_share ||--o{ location_sample : "cascade"
  contact_request }o--|| contact : "becomes on approval (no FK)"
```

Only four foreign keys exist (all `ON DELETE CASCADE`, `ON UPDATE NO ACTION`):

| Child | Parent | Effect |
|---|---|---|
| `conversation.contactId` | `contact.id` | deleting a contact deletes the conversation |
| `message.conversationId` | `conversation.id` | deleting a conversation deletes its messages |
| `location_access.contactId` | `contact.id` | deleting a contact revokes the grant |
| `location_sample.shareId` | `location_share.shareId` | deleting a share deletes its samples |

`outbox` has **no** foreign key on purpose, so deleting a conversation does not silently drop queued rows — callers must call `OutboxDao.removeByConversation` explicitly, otherwise the dispatcher keeps retrying orphans (documented on `ConversationDao.deleteById`).

A second trap is documented on `ConversationDao.update`: never use `upsert` to modify an existing conversation. `@Insert(REPLACE)` is `INSERT OR REPLACE`, which deletes the old row first and cascades away every message in the conversation.

---

## 4. Entities

Types below are the SQLite affinities from `17.json`. `?` marks a nullable column.

### 4.1 `app_metadata`

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER | PK, always 0 |
| `schemaVersion` | INTEGER | app-written marker |

### 4.2 `identity`

Single row, `id = 0`.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER | PK, 0 |
| `ed25519Public` | BLOB | identity public key |
| `identityHash` | BLOB | `SHA256(ed25519Public)` |
| `userHash` | TEXT | `vm2-…` form ([Protocol.md](Protocol.md) §8.5) |
| `displayName` | TEXT | default `""` |
| `x25519StaticPublic` | BLOB | static DH public key |
| `createdAtUnixMs` | INTEGER | |

### 4.3 `key_material`

Wrapped private keys. Aliases: `identity-ed25519`, `identity-x25519-static`.

| Column | Type | Notes |
|---|---|---|
| `alias` | TEXT | PK |
| `wrappedPrivateKey` | BLOB | `0x02 ‖ iv ‖ ct`, Keystore AES-GCM, alias-bound AAD |
| `updatedAtUnixMs` | INTEGER | |

### 4.4 `contact`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT | PK |
| `identityHash` | BLOB | **unique index** `index_contact_identityHash` |
| `ed25519Public` | BLOB | pinned identity key |
| `x25519StaticPublic` | BLOB? | pinned static key; null until learned |
| `userHash` | TEXT | |
| `displayName` | TEXT | |
| `verified` | INTEGER | boolean |
| `blocked` | INTEGER | boolean |
| `relationshipStatus` | TEXT | `ContactRelationshipStatus` name |
| `createdAtUnixMs` | INTEGER | |
| `lastSeenUnixMs` | INTEGER? | last inbound traffic |
| `pendingX25519StaticPublic` | BLOB? | key the peer presented that differs from the pin; the handshake was **refused** |
| `keyChangedAtUnixMs` | INTEGER? | when that happened |

### 4.5 `contact_request`

| Column | Type | Notes |
|---|---|---|
| `requestId` | TEXT | PK; deterministic over (requester, us) |
| `requesterIdentityHash` | BLOB | index `index_contact_request_requesterIdentityHash` |
| `requesterUserHash` | TEXT | derived from the proven identity, not the payload |
| `requesterDisplayName` | TEXT | |
| `requesterEd25519Public` | BLOB | |
| `requesterX25519StaticPublic` | BLOB? | |
| `receivedAtUnixMs` | INTEGER | |
| `status` | TEXT | `ContactRequestStatus` name |
| `rejectCount` | INTEGER | repeat requests are auto-declined silently past a threshold |

### 4.6 `location_access`

| Column | Type | Notes |
|---|---|---|
| `contactId` | TEXT | PK, FK → `contact.id` CASCADE |
| `canSeeMyLocation` | INTEGER | boolean |
| `updatedAtUnixMs` | INTEGER | |

### 4.7 `conversation`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT | PK |
| `contactId` | TEXT | FK → `contact.id` CASCADE, index |
| `lastMessageId` | TEXT? | chat-list preview pointer |
| `lastActivityUnixMs` | INTEGER | chat-list ordering |
| `unreadCount` | INTEGER | |
| `muted` | INTEGER | boolean |

### 4.8 `message`

| Column | Type | Notes |
|---|---|---|
| `messageId` | TEXT | PK; also **unique index** `index_message_messageId` |
| `conversationId` | TEXT | FK → `conversation.id` CASCADE |
| `direction` | TEXT | `MessageDirection` name |
| `contentType` | TEXT | `MessageContentType` name |
| `body` | TEXT? | chat text |
| `replyToMessageId` | TEXT? | quoted message |
| `status` | TEXT | `DeliveryStatus` name |
| `createdAtUnixMs` | INTEGER | local insert time; the ordering key |
| `sentAtUnixMs` | INTEGER? | |
| `deliveredAtUnixMs` | INTEGER? | clamped receipt time |
| `readAtUnixMs` | INTEGER? | clamped receipt time |
| `attachmentName` | TEXT? | |
| `attachmentMimeType` | TEXT? | |
| `attachmentSizeBytes` | INTEGER? | |
| `attachmentPath` | TEXT? | app-private path |
| `attachmentSha256` | BLOB? | plaintext digest from the transfer header |
| `attachmentEncrypted` | INTEGER | true when the file is in the `VMA1` container |

Indices:

| Index | Columns | Purpose |
|---|---|---|
| `index_message_conversationId` | `conversationId` | FK support |
| `index_message_messageId` | `messageId` (unique) | dedup |
| `index_message_conv_dir_status` | `conversationId, direction, status` | unread scans, `markIncomingRead` |
| `index_message_conv_created` | `conversationId, createdAtUnixMs` | windowed paging (added in v17) |

### 4.9 `outbox`

| Column | Type | Notes |
|---|---|---|
| `messageId` | TEXT | PK |
| `conversationId` | TEXT | index |
| `sealedPayload` | BLOB? | |
| `attemptCount` | INTEGER | |
| `nextAttemptUnixMs` | INTEGER | index; `0` means due now |
| `lastError` | TEXT? | stable failure code, surfaced in the chat UI |
| `receiptWaitCount` | INTEGER | re-sends after transport delivery while waiting for a receipt |

### 4.10 `session` (unused)

| Column | Type | Notes |
|---|---|---|
| `contactId` | TEXT | PK; unique index `index_session_contactId` |
| `sealedState` | BLOB | |
| `updatedAtUnixMs` | INTEGER | |

No production code reads or writes it — see §2.

### 4.11 `endpoint_cache`

| Column | Type | Notes |
|---|---|---|
| `identityHash` | BLOB | PK, index |
| `endpointsProto` | BLOB | serialized record |
| `sequence` | INTEGER | monotonic per publisher |
| `fetchedAtUnixMs` | INTEGER | |
| `expiresAtUnixMs` | INTEGER | purge key |

### 4.12 `bootstrap_node` and `relay_node`

Identical shape, one per role.

| Column | Type | Notes |
|---|---|---|
| `address` | TEXT | PK, **unique index** |
| `publicKey` | BLOB? | |
| `source` | TEXT | `BUILT_IN`, `USER`, `PEER_EXCHANGE`, `CACHED_DHT` |
| `enabled` | INTEGER | community nodes are stored **disabled** |
| `lastOkUnixMs` | INTEGER? | |
| `priority` | INTEGER | default 100 |
| `lastFailUnixMs` | INTEGER? | |
| `failCount` | INTEGER | reset to 0 by `markOk` |
| `trust` | TEXT | `NodeTrust` name, default `COMMUNITY` |
| `learnedFromHash` | BLOB? | identity hash of the peer that told us |

Ranking is **not** done in SQL: `getEnabled()` returns rows unordered and `core/common/.../network/NodeRanking.kt` applies the policy — healthy bucket first (`failCount < 3`), then `priority DESC`, `failCount ASC`, `lastOkUnixMs DESC`. Default priorities: user 150, built-in 100, official 100, community 80.

### 4.13 `location_share` and `location_sample`

| `location_share` | Type | Notes |
|---|---|---|
| `shareId` | TEXT | PK |
| `contactId` | TEXT | index |
| `direction` | TEXT | `MessageDirection` name (outgoing = we share) |
| `active` | INTEGER | boolean |
| `startedAtUnixMs` | INTEGER | |
| `endedAtUnixMs` | INTEGER? | |

| `location_sample` | Type | Notes |
|---|---|---|
| `id` | INTEGER | PK, autogenerated |
| `shareId` | TEXT | FK → `location_share.shareId` CASCADE, index |
| `latitude` / `longitude` | REAL | |
| `accuracyM` | REAL | |
| `speedMps` / `headingDeg` | REAL? | |
| `batteryPct` | INTEGER? | |
| `sampledAtUnixMs` | INTEGER | index, retention key |

### 4.14 `mailbox_blob`

| Column | Type | Notes |
|---|---|---|
| `blobId` | TEXT | PK; content-addressed `hex(SHA256(sealed))[0..32)` |
| `recipientIdentityHash` | BLOB | index |
| `sealedPayload` | BLOB | `crypto_box_seal` output; never plaintext |
| `expiresAtUnixMs` | INTEGER | index, purge key |
| `createdAtUnixMs` | INTEGER | |
| `senderIdentityHash` | BLOB? | authenticated peer that stored it; null when queued by this device. Backs the per-sender quota |

### 4.15 `dht_record`

Embedded-DHT record store (off by default).

| Column | Type | Notes |
|---|---|---|
| `recordKey` | TEXT | PK |
| `recordProto` | BLOB | |
| `sequence` | INTEGER | |
| `expiresAtUnixMs` | INTEGER | |
| `storedAtUnixMs` | INTEGER | eviction order |

---

## 5. Enums and type converters

`core/database/.../converter/EnumConverters.kt` stores every enum as `value.name` (TEXT) and reads it back with `valueOf`. An unknown stored value therefore **throws** rather than silently mapping to a default — except `NodeTrust`, which is stored as a plain `String` column and parsed by `NodeTrust.fromName`, falling back to `COMMUNITY`.

| Enum | Values | Used by |
|---|---|---|
| `MessageDirection` | `OUTGOING`, `INCOMING` | `message.direction`, `location_share.direction` |
| `MessageContentType` | `TEXT`, `LOCATION_CONTROL`, `RECEIPT`, `IMAGE`, `VIDEO`, `FILE` | `message.contentType` |
| `DeliveryStatus` | `QUEUED`, `SENT`, `DELIVERED`, `READ`, `FAILED` | `message.status` |
| `ContactRelationshipStatus` | `APPROVED`, `PENDING_OUT`, `PENDING_IN`, `REJECTED` | `contact.relationshipStatus` |
| `ContactRequestStatus` | `PENDING`, `ACCEPTED`, `REJECTED` | `contact_request.status` |
| `NodeTrust` (not a converter) | `BUILT_IN`, `USER`, `OFFICIAL`, `COMMUNITY` | `relay_node.trust`, `bootstrap_node.trust` |

Several queries hard-code the stored names (`WHERE status = 'PENDING'`, `direction = 'INCOMING'`, `status != 'READ'`), so renaming an enum constant requires a migration.

---

## 6. DAO surface

Seventeen DAOs, all in `core/database/.../dao/`. Suspend functions for one-shot work, `Flow` for anything the UI observes.

| DAO | Notable operations |
|---|---|
| `AppMetadataDao` | `get`, `upsert` |
| `IdentityDao` | `observeIdentity` (Flow), `getIdentity`, `insertIdentity`, `deleteAll` |
| `KeyMaterialDao` | `getByAlias`, `insert`, `deleteAll` |
| `ContactDao` | `observeContacts` (Flow, non-blocked, name-sorted `COLLATE NOCASE`), `getById`, `getByIdentityHash`, `getByEd25519Public`, `getAll`, `touchLastSeen`, `recordPendingKeyChange`, `insert` (ABORT on conflict), `update`, `deleteById`, `deleteAll` |
| `ContactRequestDao` | `observePending` (Flow), `getById`, `getByRequesterHash`, `rejectCountOf`, `upsert`, `update`, `deleteById`, `deleteByRequesterHash` |
| `LocationAccessDao` | `observeGranted`, `observeAll`, `getByContactId`, `grantedContactIds`, `upsert`, `deleteByContactId` |
| `ConversationDao` | `upsert`, **`update`** (never upsert an existing row — §3), `observeAll`, `observeAllWithPreview`, `observeChatList`, `getById`, `getByContactId`, `resetUnread`, `setMuted`, `deleteById`, `setLastMessageId` |
| `MessageDao` | `insert` (IGNORE on conflict), `observeConversation(cid)`, `observeConversation(cid, limit)`, `markSent`/`markDelivered`/`markRead`/`updateStatus`, `getById`, `getByIdInConversation`, `selectUnreadIncomingIds`, `markIncomingRead`, `countForConversation`, `indexOf`, `latestMessageId`, `attachmentPaths`, `deleteById`, `deleteByConversation` |
| `OutboxDao` | `enqueue`, `due(now)`, `remove`, `removeByConversation`, `getByMessageId`, `resetBackoff`, `resetBackoffFor`, `update` |
| `SessionDao` | `upsert`, `getByContactId`, `delete` — **unused** |
| `EndpointCacheDao` | `upsert`, `get`, `purgeExpired`, `delete` |
| `BootstrapNodeDao` / `RelayNodeDao` | `upsert`, `observeAll`, `observeEnabled` (bootstrap only), `getEnabled` (unordered), `getAll`, `getByAddress`, `markOk`, `markFail`, `setEnabled`, `deleteByAddress` |
| `LocationShareDao` | `upsert`, `observeActive`, `getById`, `getActiveByContact`, `getActiveByContactAndDirection`, `update`, `deleteByContact`, `allShareIds`, `deleteEndedBefore` |
| `LocationSampleDao` | `insert`, `observeLatest`, `getLatest`, `observeLatestPerShare`, `purgeOlderThan`, `deleteExcessForShare` |
| `MailboxDao` | `upsert`, `forRecipient`, `getById`, `countActive`, `countBySenderSince`, `delete`, `deleteForRecipient`, `purgeExpired` |
| `DhtRecordDao` | `active(now)`, `count`, `upsert`, `purgeExpired`, `evictOldest` |

Three projection types keep the UI off N+1 queries:

| Type | Built by | Contents |
|---|---|---|
| `ChatListRow` | `ConversationDao.observeChatList` | one JOIN over `conversation` + `contact` + last `message`; the chat list renders it verbatim |
| `ConversationWithPreview` | `ConversationDao.observeAllWithPreview` | conversation plus the last message body |
| `MessageWithReply` | `MessageDao.observeConversation(cid, limit)` | message + quoted-message preview (self LEFT JOIN) + the outbox `lastError` |

`observeConversation(cid, limit)` is the paging query: `ORDER BY createdAtUnixMs DESC, messageId DESC LIMIT :limit`, rendered with `reverseLayout = true` so a new message never shifts the scroll anchor. "Load earlier" grows `limit`. `indexOf(cid, messageId)` returns the zero-based position in that same total order (or `-1`) so the window can be grown until a quoted message is inside it.

---

## 7. Migrations

`core/database/.../migration/Migrations.kt`; all sixteen are registered in `DatabaseModule`.

| Step | Adds |
|---|---|
| 1 → 2 | `identity`, `key_material` |
| 2 → 3 | `contact` + unique index on `identityHash` |
| 3 → 4 | `endpoint_cache`, `bootstrap_node` |
| 4 → 5 | `conversation`, `message`, `outbox`, `session` (+ their indices) |
| 5 → 6 | `location_share`, `location_sample` |
| 6 → 7 | `contact.x25519StaticPublic` |
| 7 → 8 | `bootstrap_node.priority` / `lastFailUnixMs` / `failCount`; new `relay_node` table |
| 8 → 9 | `mailbox_blob` |
| 9 → 10 | `dht_record` |
| 10 → 11 | `identity.displayName` |
| 11 → 12 | `contact.relationshipStatus`; `contact_request`; `location_access` |
| 12 → 13 | `message.attachmentName` / `attachmentMimeType` / `attachmentSizeBytes` / `attachmentPath` |
| 13 → 14 | `contact_request.rejectCount` |
| 14 → 15 | `outbox.receiptWaitCount` |
| 15 → 16 | **Protocol v2 batch** — see below |
| 16 → 17 | `CREATE INDEX index_message_conv_created ON message(conversationId, createdAtUnixMs)` |

### 15 → 16 in detail

Exported as `MIGRATION_15_16_STATEMENTS` so a plain-SQLite test can replay it without Room:

- Static-key pinning: `contact.pendingX25519StaticPublic`, `contact.keyChangedAtUnixMs`.
- Mailbox per-sender quota: `mailbox_blob.senderIdentityHash`.
- Attachments: `message.attachmentSha256`, `message.attachmentEncrypted` (default 0), index `index_message_conv_dir_status`.
- Node trust: `trust` (default `'COMMUNITY'`) and `learnedFromHash` on both `relay_node` and `bootstrap_node`; existing rows are back-filled from `source` (`BUILT_IN` → `BUILT_IN`, `USER` → `USER`); rows whose source is `PEER_EXCHANGE` or `CACHED_DHT` are **disabled** (`enabled = 0`), which is the data-layer half of the node-trust model.

### 16 → 17 in detail

Also exported as `MIGRATION_16_17_STATEMENTS`. `MessageDao.observeConversation(cid, limit)` runs `WHERE conversationId = ? ORDER BY createdAtUnixMs DESC LIMIT ?`; without the composite index SQLite sorted the whole conversation on every emission. The index turns the window into a bounded index scan.

### Gaps in the exported schema history

`core/database/schemas/ir.vmessenger.core.database.VMessengerDatabase/` contains `1, 2, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17.json`. **Versions 3, 4, 5 and 11 have no exported JSON** because they were never committed as released database versions — the migrations exist (and run) but the intermediate schema was folded into the next commit before export. The gap is expected and is not a missing-file bug. Room only validates against the *current* version's JSON, and the migration chain is continuous, so upgrades from any shipped version still work.

---

## 8. Reactive queries

DAOs return `Flow<T>` for anything the UI observes; Room re-emits on every write to the underlying tables. Repositories map entities to domain models and the ViewModels expose `StateFlow`. Nothing in the UI polls the database.

The chat list is a single `observeChatList()` flow, so adding a contact, receiving a message and a status change all arrive as one recomposition rather than a per-row fan-out.

---

## 9. Retention and deletion

| Scope | Behaviour |
|---|---|
| Delete one message | `MessageDao.deleteById` — local copy only, nothing is sent to the peer |
| Delete a conversation | `ConversationDao.deleteById` cascades messages; the caller must also `OutboxDao.removeByConversation` and erase files listed by `MessageDao.attachmentPaths` |
| Delete a contact | cascades `conversation` (and therefore `message`) and `location_access`; `MailboxDao.deleteForRecipient` and `ContactRequestDao.deleteByRequesterHash` clean up the rest |
| Location retention | `LocationSampleDao.purgeOlderThan` (window) and `deleteExcessForShare` (cap per share); `LocationShareDao.deleteEndedBefore` removes finished shares and cascades their samples |
| Endpoint cache | `purgeExpired(now)` |
| Mailbox | `purgeExpired(now)`; per-sender quota via `countBySenderSince` |
| DHT records | `purgeExpired(now)` plus `evictOldest(excess)` by `storedAtUnixMs` |
| Secure wipe | `clearAllTables()` → `close()` → delete `vmessenger.db` and its journal siblings → destroy the Keystore master key ([Security.md](Security.md) §9) |

---

## 10. Performance notes

- Every foreign key column is indexed, so cascades and joins do not table-scan.
- `index_message_conv_created` (v17) and `index_message_conv_dir_status` (v16) cover the two hot message queries: windowed paging and the unread scan.
- `outbox(nextAttemptUnixMs)` is indexed so `due(now)` is a range scan; the dispatcher drains at most 8 conversations in parallel.
- Node ranking is done in Kotlin (`NodeRanking`), not SQL, so the policy is unit-testable and the DAO stays a plain query catalogue.
- `LocationSampleDao.observeLatestPerShare` uses `id IN (SELECT MAX(id) … GROUP BY shareId)`, which is reactive on the sample table so the map refreshes on every new position rather than only when shares start and stop.
