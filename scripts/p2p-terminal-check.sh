#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== vMessenger P2P terminal check ==="
echo ""
echo "P2PConfig defaults (from source):"
grep -E 'var (peerCache|relayPeer|natTraversal)' core/common/src/main/kotlin/ir/vmessenger/core/common/network/P2PConfig.kt || true
echo ""

echo "Running P2P-focused unit tests..."
./gradlew \
  :data:testDebugUnitTest --tests 'ir.vmessenger.data.network.RelaySelectionTest' \
  :data:testDebugUnitTest --tests 'ir.vmessenger.data.network.RelayPublishAlignmentTest' \
  :data:testDebugUnitTest --tests 'ir.vmessenger.data.network.P2PConfigLoaderTest' \
  :core:common:test --tests 'ir.vmessenger.core.common.network.NetworkPathTrackerTest' \
  :network:messaging:testDebugUnitTest --tests 'ir.vmessenger.network.messaging.PeerRelayServiceTest' \
  :network:dht:testDebugUnitTest --tests 'ir.vmessenger.network.dht.EmbeddedDhtRoutingTableTest' \
  --quiet

# The app ships no node of its own: check the one you run, e.g. NODE_HEALTH_URL=https://node.example.org/healthz
NODE_URL="${NODE_HEALTH_URL:-}"
echo ""
echo "Node health (${NODE_URL:-no NODE_HEALTH_URL given}):"
if [[ -z "$NODE_URL" ]]; then
  echo "(skipped: set NODE_HEALTH_URL to your node's /healthz)"
elif command -v curl >/dev/null 2>&1; then
  curl -fsSL --max-time 5 "$NODE_URL" || echo "(unreachable)"
else
  echo "curl not installed"
fi
echo ""
echo "Done. See docs/Testing.md for the two-emulator scenario matrix."
