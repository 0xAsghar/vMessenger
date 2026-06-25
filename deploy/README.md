# Deploying relay.vmessenger.ir

## Build the node

```bash
./gradlew :node:installDist
```

Artifacts land in `node/build/install/node/`. Copy to the VPS:

```bash
rsync -av node/build/install/node/ root@2.144.26.49:/opt/vmessenger/
```

## VPS setup

1. Install JDK 21 and nginx.
2. Copy `deploy/nginx/relay.vmessenger.ir.conf` to `/etc/nginx/sites-available/` and enable it.
3. Ensure Let's Encrypt certs exist at:
   - `/etc/letsencrypt/live/relay.vmessenger.ir/fullchain.pem`
   - `/etc/letsencrypt/live/relay.vmessenger.ir/privkey.pem`
4. Copy `deploy/systemd/vmessenger-relay.service` to `/etc/systemd/system/`.
5. Create user `vmessenger`, set ownership of `/opt/vmessenger`.
6. Enable and start:

```bash
systemctl daemon-reload
systemctl enable --now vmessenger-relay
systemctl reload nginx
```

7. Verify: `curl https://relay.vmessenger.ir/healthz` → `ok`

On the VPS (bypassing CDN):

```bash
curl -sk https://2.144.26.49/healthz -H 'Host: relay.vmessenger.ir'
# → ok
```

If the domain times out but the direct-origin check above works, configure Arvan CDN below.

## Arvan Cloud CDN

DNS for `relay.vmessenger.ir` should point at Arvan (e.g. `185.143.233.x`), not directly at the VPS IP.

In the Arvan panel for `relay.vmessenger.ir`:

1. **Origin / upstream:** `2.144.26.49`, port **443**, protocol **HTTPS** (origin-pull to nginx).
2. **SSL:** full / HTTPS to origin (nginx uses Let's Encrypt).
3. **WebSocket:** enable for `/dht` and `/relay`.
4. **Headers:** forward `Upgrade` and `Connection` to origin.
5. **Timeouts:** origin read timeout ≥ 86400s for `/relay`.
6. **Buffering:** disable for `/relay`.
7. **Firewall:** allow inbound **TCP 443** from Arvan origin-pull (and open 443 for direct tests if needed).

After saving, verify: `curl -sS https://relay.vmessenger.ir/healthz`

If WebSocket through CDN fails, point DNS A/AAAA directly at the VPS (nginx still terminates TLS).

## Ports

| Path | Purpose |
|------|---------|
| `wss://relay.vmessenger.ir/dht` | DHT bootstrap + store/find |
| `wss://relay.vmessenger.ir/relay` | Circuit relay (listener + dial) |
| JVM listens `127.0.0.1:8443` | Behind nginx |

## Local emulator dev

```bash
./gradlew :node:run --args="--tcp"   # TCP DHT on :46555
./scripts/emulator-connect.sh
```

In the app Debug screen, use **Join & Publish** (sets `NetworkConfig.useDevBootstrap = true`).

Or start the service with dev bootstrap:

```bash
adb shell am startservice -n ir.vmessenger.android/ir.vmessenger.app.network.NetworkLifecycleService \
  --ei listen_port 48555 --ei forward_port 48555 --ez use_dev_bootstrap true
```
