# Deployment — running a vMessenger relay/DHT node

This is the operator runbook for the reference node in [`node/`](../node) (Ktor; runs on any JRE 17 or newer).
It replaces the old `deploy/README.md`. Everything below is what the canonical installer
[`scripts/setup-node.sh`](../scripts/setup-node.sh) does; you can run it from a repo checkout,
from a downloaded release tarball, or as a one-liner on a fresh Ubuntu/Debian host.

## 1. What a node is

- The JVM process listens on **`127.0.0.1:<node-port>`** (default `8443`) over plain HTTP/WebSocket. It never
  terminates TLS itself.
- **nginx** owns `:80`/`:443`, terminates TLS and proxies three routes: `GET /healthz` (`ok`), `WS /dht`
  (one `DhtRpcRequest` → one `DhtRpcResponse`), `WS /relay` (listener control channels + bridged circuits).
- State lives in `/var/lib/vmessenger` (`node.seed` — the persisted random node id; `tmp/` for the JVM's temporary files).
- Operator overrides go in `/etc/vmessenger/node.env` (`VMESSENGER_*` variables — see
  [`NodeConfig`](../node/src/main/kotlin/ir/vmessenger/node/NodeConfig.kt) for the full list: limits, rate
  limits, proof skew, advertised DHT URL, peer nodes, `VMESSENGER_TRUST_PROXY`).
- Apps reach the default node at `wss://relay.vmessenger.ir/{dht,relay}` (`NetworkConfig.kt`); any other node is
  added in the app under **تنظیمات → گره‌های شبکه** with a `vmnode:bootstrap:wss://…/dht` /
  `vmnode:relay:wss://…/relay` link or its QR (the installer prints both).

## 2. Requirements

- Ubuntu 24.04 / Debian 12 host with a public IP; 1 vCPU / 1 GB RAM is enough (the unit caps the JVM at 512 MB).
- Root SSH access.
- A DNS name pointing at the host **or** at a CDN whose origin is the host (see §5 for Arvan).
- Inbound TCP 22, 80, 443 (`--firewall` configures ufw for exactly that).

## 3. Install / update

### 3.1 From a release tarball (recommended on servers)

Build the tarball on a workstation (needs the repo's JDK and Android SDK for Gradle configuration):

```bash
JAVA_HOME=$PWD/.jdk/jdk-17/Contents/Home ./gradlew :node:distTar
# -> node/build/distributions/vmessenger-node-<version>.tar.gz
```

Copy the installer, the deploy templates and the tarball to the host, then run the installer with
`VMESSENGER_REPO` pointing at the staging directory (it only needs `deploy/` from it):

```bash
ssh root@<host> 'install -d /root/vmessenger-deploy/deploy/nginx /root/vmessenger-deploy/deploy/systemd /root/vmessenger-deploy/scripts'
scp scripts/setup-node.sh root@<host>:/root/vmessenger-deploy/scripts/
scp deploy/nginx/vmessenger-node.conf.template deploy/nginx/arvan-ips.conf root@<host>:/root/vmessenger-deploy/deploy/nginx/
scp deploy/systemd/vmessenger-node.service.template root@<host>:/root/vmessenger-deploy/deploy/systemd/
scp node/build/distributions/vmessenger-node-<version>.tar.gz root@<host>:/root/vmessenger-deploy/
ssh root@<host> 'VMESSENGER_REPO=/root/vmessenger-deploy /root/vmessenger-deploy/scripts/setup-node.sh \
  --domain relay.example.com --tls letsencrypt --acme-no-email --behind-cdn none --firewall \
  --dist-tar /root/vmessenger-deploy/vmessenger-node-<version>.tar.gz'
```

The installer is idempotent: re-running it re-renders the unit and the nginx site, reinstalls the node
files, restarts `vmessenger-node`, reloads nginx and health-checks `127.0.0.1:<port>/healthz` and
`https://<domain>/healthz` through nginx. Use `--skip-cert` on re-runs once a certificate exists.

Once a GitHub Release carries `vmessenger-node-<version>.tar.gz`, the one-liner form downloads it itself:

```bash
curl -fsSL https://raw.githubusercontent.com/0xAsghar/vMessenger/main/scripts/setup-node.sh | sudo bash -s -- --domain relay.example.com --acme-email ops@example.com
```

### 3.2 Flags

| Flag | Meaning |
|---|---|
| `--domain HOST` | Public hostname (TLS SAN + advertised URLs). Enables Let's Encrypt by default. |
| `--ip ADDRESS` | Public IP when there is no domain (default: auto-detected). Self-signed TLS only. |
| `--tls letsencrypt\|selfsigned` | Default: `letsencrypt` with `--domain`, else `selfsigned`. |
| `--acme-email EMAIL` / `--acme-no-email` | Let's Encrypt account contact (one of the two is required for issuance). |
| `--behind-cdn arvan\|none` | Installs the real-IP snippet (`deploy/nginx/arvan-ips.conf`) so nginx and the node see client IPs. |
| `--firewall` | ufw: allow OpenSSH, 80/tcp, 443/tcp; deny other inbound. |
| `--dist-tar PATH` / `--dist-url URL` | Install this tarball instead of the latest GitHub release. |
| `--build` | Build from a repo checkout (JDK 21 + Android SDK required — prefer a tarball on servers). |
| `--skip-cert` / `--force-cert` | Reuse existing certificates / regenerate the self-signed one. |
| `--install-dir`, `--cert-dir`, `--node-port` | Paths and port (defaults `/opt/vmessenger`, `/etc/vmessenger/tls`, `8443`). |
| `--dev` | Run the raw-TCP DHT dev node on `:46555` for emulator testing (no nginx/systemd). |

### 3.3 What gets written

| Path | Content |
|---|---|
| `/opt/vmessenger/{bin,lib}` | The node distribution (owned by the `vmessenger` system user). |
| `/etc/systemd/system/vmessenger-node.service` | Rendered from `deploy/systemd/vmessenger-node.service.template` (hardened unit, `Restart=always`). |
| `/etc/nginx/sites-available/vmessenger-node.conf` | Rendered from `deploy/nginx/vmessenger-node.conf.template` (`:80` ACME + redirect, `:443` routes, rate/conn limits). |
| `/etc/nginx/snippets/vmessenger-realip.conf` | Real-IP restoration (empty unless `--behind-cdn`). |
| `/etc/vmessenger/node.env` | Optional overrides (created with commented defaults). |
| `/etc/vmessenger/tls/` | Self-signed bootstrap certificate. |
| `/etc/letsencrypt/live/<domain>/` | Let's Encrypt certificate; renewed by `certbot.timer` with `systemctl reload nginx` as deploy hook. |

The installer also removes the pre-1.0 artifacts (`vmessenger-relay.service`, `relay.vmessenger.ir.conf`) if it
finds them, so two units can never fight over the same port. It leaves every other nginx site alone — earlier
versions deleted `sites-enabled/default`, which is somebody else's site on a shared host.

### 3.4 Exit status

Every fatal error prints `error: [CODE] message`, and the exit status says what kind of problem it was:

| Status | Meaning |
|---|---|
| `0` | Installed and healthy. |
| `1` | Failed; the code says where. |
| `10` | Needs a decision before it can continue. |
| `20` | This server cannot run a node (`OS_UNSUPPORTED`, `ARCH_UNSUPPORTED`, `NO_SYSTEMD`). |
| `30` | Fix something and run again (`NOT_ROOT`, `RAM_TOO_LOW`, `DISK_LOW`). |
| `40` | Another install is running (`INSTALL_BUSY`). |

## 4. TLS

Clients validate the certificate they see. Behind a CDN that is the CDN's edge certificate; without a CDN it is
the origin's, so the origin must present a publicly trusted chain (Let's Encrypt).

1. **Let's Encrypt via HTTP-01 (default).** The installer first brings nginx up on a self-signed bootstrap cert,
   probes `http://<domain>/.well-known/acme-challenge/` (through the CDN if there is one), runs
   `certbot certonly --webroot -w /var/www/acme -d <domain>` and switches nginx to the issued chain. Renewal is
   automatic (`certbot.timer`); verify with `certbot renew --dry-run`.
2. **DNS-01** when port 80 cannot reach the origin: `certbot certonly --manual --preferred-challenges dns -d <domain>`,
   add the TXT record, then re-run the installer with `--tls letsencrypt --skip-cert`.
3. **Self-signed only** (`--tls selfsigned`) is fine for CDN→origin pulls in a non-strict SSL mode and for
   private test nodes; apps will reject it unless a CDN fronts the host.

## 5. Behind Arvan CDN (relay.vmessenger.ir)

Panel checklist (labels vary by panel version):

1. DNS record for the node name → the origin IP, proxy (cloud) **on**.
2. Origin/upstream: port **443**, protocol **HTTPS**; SSL mode *full* until Let's Encrypt is installed on the
   origin, then *full (strict)*.
3. WebSocket support enabled (for `/dht` and `/relay`).
4. Cache bypass for `/healthz`, `/dht`, `/relay`; origin read timeout as high as the plan allows for `/relay`
   (the app sends a keepalive frame every 40 s, so idle drops are tolerated but cost a reconnect).
5. Copy Arvan's origin-pull CIDRs into `set_real_ip_from` lines in `/etc/nginx/snippets/vmessenger-realip.conf`
   (template: `deploy/nginx/arvan-ips.conf`) and `nginx -t && systemctl reload nginx` — without them per-IP limits
   see the CDN edge address, so they stay deliberately generous.

Never publish the origin IP in docs or the repo; the CDN is the only public entry point.

## 6. Verify

```bash
# Origin directly (works before the CDN switch; -k while still self-signed)
curl -sk --resolve <domain>:443:<origin-ip> https://<domain>/healthz          # ok
# Through the public name / CDN
curl -sS https://<domain>/healthz                                             # ok
curl -sS 'https://<domain>/healthz?verbose=1'                                 # JSON counters (listeners, circuits, rejected…)
# Certificate chain and dates
openssl s_client -connect <domain>:443 -servername <domain> </dev/null 2>/dev/null | openssl x509 -noout -subject -issuer -dates
# WebSocket upgrade must answer 101
curl -s -i --http1.1 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' https://<domain>/relay | head -1
# On the host
systemctl status vmessenger-node --no-pager; journalctl -u vmessenger-node -f
ss -ltnp | grep -E ':(80|443|8443) '        # 8443 must be loopback only
nginx -t; certbot renew --dry-run
```

From the app (تنظیمات → اشکال‌زدایی → لاگ‌ها, or `files/logs/vmessenger.log` on a debug build) a healthy node shows
`Dht: bootstrap OK`, `Dht: publish/store OK`, `Relay: control channel connected`; a `Chain validation failed` line
means the client does not trust the certificate it sees (usually a wrong device clock — the app shows a banner — or a
self-signed origin exposed without a CDN).

## 7. Operate

- Logs are single-line `key=value` events via journald: `listener_registered`, `listener_closed`, `dial`,
  `circuit_opened/closed`, `dht_store`, `rejected reason=…`. Client IPs are logged only at DEBUG.
- `/healthz?verbose=1` exposes gauges (`listeners`, `pendingDialers`, `activeCircuits`, `dhtRecords`) and
  rejection counters (`staleProof`, `replayedProof`, `rateLimited`, `relayFull`, `listenerBusy`, `invalidHello`).
- Limits and rate limits are env-tunable in `/etc/vmessenger/node.env`; restart the unit after editing.
- A restart closes every listener with `GOING_AWAY`; apps reconnect within a second. DHT records and listener
  registrations are in-memory and are re-published by the apps (records expire after 20 minutes anyway).
- Redeploy = build a new tarball, `scp`, re-run the installer with `--dist-tar … --skip-cert`. Keep the previous
  tarball on the host to roll back the same way.
- Second node: run the installer on another host and, on the first node, set
  `VMESSENGER_PEER_NODES=wss://<other>/dht` so `findNode` advertises it; users add it in the app via its
  `vmnode:` link.
