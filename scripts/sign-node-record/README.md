# sign-node-record

Operator-signed `SignedNodeRecord`s let the app import a relay/bootstrap node as
**OFFICIAL** (enabled automatically, priority 100). Every other valid record a
peer sends is stored as **COMMUNITY** — disabled until the user enables it in
Settings → Nodes — so no peer can hijack the active relay.

## Trust anchor

The app embeds the operator's Ed25519 public key in
`core/common/src/main/kotlin/ir/vmessenger/core/common/network/NetworkConfig.kt`
as `OPERATOR_ED25519_PUBLIC_KEY_HEX` (64 hex chars).

**The constant is currently a placeholder (all zeros) and must be set before the
1.0 release.** While it is zero `NetworkConfig.operatorEd25519PublicKey()` returns
`null` and no record can become OFFICIAL.

## Key generation (offline, once)

Generate the key pair on an offline machine with libsodium, e.g. with the
`:node` module's tooling or any libsodium binding (`crypto_sign_keypair`):

- keep the 64-byte secret key in the operator's offline key store (never in the repo);
- paste the 32-byte public key, lower-case hex, into `OPERATOR_ED25519_PUBLIC_KEY_HEX`.

## Signing a record

A record is signed over the v2 transcript (see `SignedNodeRecordVerifier` in
`data/` and `docs/Security.md`):

```
"vmessenger-node-record-v2" || lpUtf8(address) || u32be(role) || lp(public_key)
  || u32be(n) || lpUtf8(capability)* || u64be(expires_at_unix_ms)
```

with `transcript_version = 2`, `role` ∈ {`NODE_ROLE_BOOTSTRAP` = 1, `NODE_ROLE_RELAY` = 2},
`public_key` = the operator public key, and `address` a `wss://host[/path]` URL
(release builds reject anything else, even when operator-signed).

The signing CLI itself is maintained by the relay/DHT server agent alongside the
`:node` module; this note documents the contract the app verifies against.
Rotating the operator key requires an app release (the key is compiled in).
