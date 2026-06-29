#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== vMessenger P2P terminal check ==="
echo ""
echo "P2PConfig defaults (from source):"
grep -E 'var (multiNode|relayPeer|natTraversal)' core/common/src/main/kotlin/ir/vmessenger/core/common/network/P2PConfig.kt || true
echo ""

echo "Running P2P-focused unit tests..."
./gradlew \
  :data:testDebugUnitTest --tests 'ir.vmessenger.data.network.RelaySelectionTest' \
  :data:testDebugUnitTest --tests 'ir.vmessenger.data.network.RelayPublishAlignmentTest' \
  :data:testDebugUnitTest --tests 'ir.vmessenger.data.network.P2PConfigLoaderTest' \
  :core:common:testDebugUnitTest --tests 'ir.vmessenger.core.common.network.NetworkPathTrackerTest' \
  :network:messaging:testDebugUnitTest --tests 'ir.vmessenger.network.messaging.PeerRelayServiceTest' \
  :network:dht:testDebugUnitTest --tests 'ir.vmessenger.network.dht.EmbeddedDhtRoutingTableTest' \
  --quiet

NODE_URL="${NODE_HEALTH_URL:-https://relay.vmessenger.ir/healthz}"
echo ""
echo "Node health (${NODE_URL}):"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL --max-time 5 "$NODE_URL" || echo "(unreachable)"
else
  echo "curl not installed"
fi
echo ""
echo "Done. See docs/P2P-Testing.md for real-device matrix."
