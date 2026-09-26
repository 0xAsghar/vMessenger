# sign-node-record

Operator-signed `SignedNodeRecord`s let the app import a relay/bootstrap node as
**OFFICIAL** (enabled automatically, priority 100). Every other valid record a
peer sends is stored as **COMMUNITY** — disabled until the user enables it in
Settings → Nodes — so no peer can hijack the active relay.

Records travel only in peer exchange between approved contacts, which is off by
default (`P2PConfig.DEFAULT_PEER_EXCHANGE = false`; the Debugging screen can turn
it on).

## Trust anchor

The app embeds the operator's Ed25519 public key in
`core/common/src/main/kotlin/ir/vmessenger/core/common/network/NetworkConfig.kt`
as `OPERATOR_ED25519_PUBLIC_KEY_HEX` (64 hex chars).

**The constant is still a placeholder (all zeros) in 2.0.1.** While it is zero
`NetworkConfig.operatorEd25519PublicKey()` returns `null` and no record can become
OFFICIAL: every valid record a peer sends is a COMMUNITY node.

## Key generation (offline, once)

Generate the key pair on an offline machine with any libsodium binding
(`crypto_sign_keypair`); the repository ships no key tool:

- keep the 64-byte secret key in the operator's offline key store (never in the repo);
- paste the 32-byte public key, lower-case hex, into `OPERATOR_ED25519_PUBLIC_KEY_HEX`.

## Signing a record

A record is signed over the v2 transcript (see `SignedNodeRecordVerifier` in
`data/src/main/kotlin/ir/vmessenger/data/network/SignedNodeRecordVerifier.kt`,
`docs/Protocol.md` §12 and `docs/Security.md` §6):

```
"vmessenger-node-record-v2" || lpUtf8(address) || u32be(role) || lp(public_key)
  || u32be(n) || lpUtf8(capability)* || u64be(expires_at_unix_ms)
```

with `transcript_version = 2`, `role` ∈ {`NODE_ROLE_BOOTSTRAP` = 1, `NODE_ROLE_RELAY` = 2},
`public_key` = the operator public key, and `address` a `wss://host[:port][/path]`
URL, optionally pinned with `#pin-sha256=…` (`NodeAddressPolicy`; release builds
reject anything else, even when operator-signed).

There is no signing tool in the repository: this directory holds only this note,
which documents the contract the app verifies against. `SignedNodeRecordSigner`
(in the same file as the verifier, in the Android `:data` module) is the reference
implementation: the app uses it to sign the records it sends with the person's own
identity key, which is why those arrive as COMMUNITY. A signing tool must produce
the same transcript and a libsodium `crypto_sign_detached` signature over it.
Rotating the operator key requires an app release (the key is compiled in).
