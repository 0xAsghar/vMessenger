#!/usr/bin/env bash
# Forward ports for two-emulator vMessenger development.
# Usage: ./scripts/emulator-connect.sh [listen_port] [forward_port]
set -euo pipefail

LISTEN_PORT="${1:-48555}"
FORWARD_PORT="${2:-48555}"
BOOTSTRAP_PORT=46555

echo "Forwarding bootstrap node (host DHT) to emulators..."
adb forward tcp:"$BOOTSTRAP_PORT" tcp:"$BOOTSTRAP_PORT"

echo "Forwarding peer listen port $LISTEN_PORT on emulator -> host $FORWARD_PORT..."
adb forward tcp:"$FORWARD_PORT" tcp:"$LISTEN_PORT"

echo "Done. For TCP dev node: ./gradlew :node:run --args=\"--tcp\""
echo "Production node uses WebSocket on :8443 behind nginx (see deploy/README.md)."
