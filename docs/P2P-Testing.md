# vMessenger P2P Real-Device Test Matrix

Use this checklist when validating P2P migration phases on real hardware. Record results from **both devices** under **تنظیمات → اشکال‌زدایی** (network path, diagnostics snapshot, P2P flags).

See also [P2P-Bugs-Improvement.md](P2P-Bugs-Improvement.md) and [P2P-Phases.md](P2P-Phases.md).

## Prerequisites

- Two Android devices (or one device + one emulator for limited scenarios)
- Optional: user-run bootstrap/relay node (`scripts/setup-node.sh`)
- Debug logging enabled on both devices

## Scenarios

| # | Scenario | Discovery path | Transport path | Handshake | Delivery | Battery notes |
|---|----------|----------------|----------------|-----------|----------|---------------|
| 1 | Two emulators, local TCP DHT | | | | | |
| 2 | Two physical devices, same Wi-Fi | | | | | |
| 3 | Two physical devices, different Wi-Fi | | | | | |
| 4 | One Wi-Fi + one mobile data | | | | | |
| 5 | Both on mobile data | | | | | |
| 6 | Default relay disabled (`reduceDefaultRelay`) | | | | | |
| 7 | All built-in nodes disabled, user node only | | | | | |
| 8 | Cached peer only (`peerCache` on, DHT off) | | | | | |
| 9 | Peer exchange only | | | | | |
| 10 | Relay-peer mode, three devices | | | | | |
| 11 | Mailbox delivery, recipient offline | | | | | |

## Per-scenario checklist

For each run record:

1. **P2P flags** active on both devices
2. **Discovery path** — cache, DHT, peer exchange, default fallback
3. **Transport path** — DIRECT, UDP_ATTEMPT, USER_RELAY, DEFAULT_RELAY, STORE_AND_FORWARD
4. **Handshake** — success or failure reason
5. **Message delivery** — first message, reply, after app restart
6. **Debug diagnostics** — active relay URL, DHT on/off, mailbox pending, relay circuits
7. **Logs** — export from both devices (`adb logcat` or in-app logs)

## Quick terminal check

```bash
./scripts/p2p-terminal-check.sh
```

Runs focused unit tests and prints current `P2PConfig` defaults.

## Pass criteria (rc25–rc30)

- Published relay URL matches listener relay (multi-node list)
- P2P flags persist across app restart
- UDP attempts labeled correctly (not claimed as full NAT traversal)
- User relay requires opt-in; circuits visible in debug when active
- Embedded DHT respects battery/Wi-Fi gate when enabled
- Signed peer records reject tampered imports
- Outbox falls back to local mailbox queue after max send attempts
