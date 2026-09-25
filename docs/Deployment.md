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

- **Ubuntu 22.04, 24.04 or 26.04, or Debian 12 or 13**, 64-bit x86 or ARM, with systemd. Ubuntu 20.04 and
  Debian 11 install with a warning: they no longer get regular security updates. A newer release installs after
  `--allow OS_UNTESTED`; anything else is refused (`OS_UNSUPPORTED`, exit 20). Every supported release was
  installed end to end in the Docker harness (Testing §2.1).
- At least 450 MB of memory (under 1 GB, the installer adds a 1 GB swapfile) and about 1.5 GB free on `/var`.
  The JVM heap is a quarter of the memory, between 128 and 768 MB.
- A JRE 17 or newer. The installer reuses one that is installed, or installs the first of OpenJDK 21, 25 and 17
  that apt offers (Debian 11 and 12 have 17 only) and points the unit's `JAVA_HOME` at it.
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
| `--build` | Build from a repo checkout (JDK 17 + the Gradle toolchain's 21 + Android SDK — prefer a tarball on servers). |
| `--skip-cert` / `--force-cert` | Reuse existing certificates / regenerate the self-signed one. |
| `--install-dir`, `--cert-dir`, `--node-port` | Paths and port (defaults `/opt/vmessenger`, `/etc/vmessenger/tls`, `8443`). |
| `--dev` | Run the raw-TCP DHT dev node on `:46555` for emulator testing (no nginx/systemd). |
| `--allow CODE[,CODE]` | Go ahead where the installer would stop to ask: `OS_UNTESTED`, `CLOCK_SKEW` (§8.5). |
| `--clock-offset-ms N` | How far the server's clock is behind a trusted one, in ms (the app measures it). |
| `--apt-mirror URL` | Fetch packages from this mirror for this install; the server's sources stay as they are. |
| `--offline` | Download nothing but apt packages and certificates (needs `--dist-tar` or `--bundle-dir`). |
| `--bundle-dir DIR` | Templates and tarball from an app-style bundle, checked against its `SHA256SUMS` (§8). |

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

## 8. Machine mode — the protocol the app speaks

The app's **New node** drives this same script over SSH. It uploads a *bundle* — `setup-node.sh`,
`deploy/`, `vmessenger-node-<version>.tar.gz`, `manifest.json` (`{"protocol": 1, "nodeVersion": "…"}`)
and `SHA256SUMS` — to `~/.vmessenger-installer/<version>/` and runs it with `--from-app`. Machine mode
never downloads anything but apt packages and certificates: no GitHub, no clone, no tarball fetch.

### 8.1 Invocations

All but `--version` run as root (`sudo`).

| Command | Does |
|---|---|
| `--version` | `vmessenger-installer protocol=1 bundle=<version>` |
| `--from-app --bundle-dir B --preflight [install options]` | Facts and blocking problems; changes nothing. |
| `--from-app --bundle-dir B --launch [install options]` | Checks `SHA256SUMS`, copies the bundle to `/var/lib/vmessenger-installer/bundles/<version>/` (root-owned), starts the install as the transient unit `vmessenger-install-<run>` and returns its run id at once. |
| `--from-app --follow RUN [--from-byte N]` | The run's log from byte `N`, streamed until the run ends; the exit status is the run's. |
| `--from-app --status RUN` | One `status` marker: `state=running\|done\|failed\|lost exit=<n> bytes=<log size>`. |
| `--from-app --result RUN` | The run's `result.json`. |
| `--list-runs` | Every run, newest first. |

The install runs under systemd, not under the SSH session: a dropped connection, a phone that goes
to sleep or an app that is killed does not stop it. The app reconnects and resumes `--follow` from the
end of the last complete line it has; nothing is lost or repeated. Only one install runs at a time
(`flock` on `/var/lib/vmessenger-installer/lock`); a second `--launch` stops with `INSTALL_BUSY` (exit
40) and a `fact key=active_run` naming the one in progress.

### 8.2 Markers

Everything the app acts on is a line of this form; every other line is log text for a person.

```
##vm v=1 seq=<n> ts=<epoch ms> ev=<event> key=value …
```

Values are percent-encoded byte by byte: anything outside `A–Z a–z 0–9 . _ ~ : / @ + , -` becomes
`%XX`. `seq` rises by one per line within an invocation, so a resumed stream can be de-duplicated.

| Event | Keys | Meaning |
|---|---|---|
| `hello` | `proto`, `installer`, `action`, `run` | First line of every invocation. |
| `fact` | `key`, `value` | Something learned about the server (`os_id`, `arch`, `node_id`, `active_run`, …). |
| `step` | `id`, `state`, `note` | `state` is `start`, `ok`, `skip`, `warn`, `fail` or `wait`. |
| `issue` | `code`, `severity`, `step`, `detail` | `severity` is `fatal`, `consent`, `warn` or `info`. A fatal issue is followed by the step's `fail` and the end. `consent` is a decision (§8.5). |
| `launched` | `run` | `--launch` started a run. |
| `result` | `status`, `file` | `result.json` was written (`status` is `ok` or `failed`). |
| `status` | `run`, `state`, `exit`, `bytes` | Answer to `--status`. |
| `end` | `status` | Last line of every invocation and of every run log; `status` is the exit status. |

Step ids, in order: `preflight`, `apt`, `swap`, `java`, `packages`, `ports`, `firewall`, `files`,
`tls`, `config`, `service`, `health`, `finish`. `wait` means the step is waiting on something outside the
installer — cloud-init, or another package manager holding the dpkg lock — for up to 15 minutes. Exit statuses are those of §3.4. A command that fails outside a known check is
reported as `INTERNAL` with its line, and exits 1.

### 8.3 `result.json` (schema 1)

Written at the end of every run, failed ones included, to the run directory; a successful run also
copies it to `/etc/vmessenger/install.json`, which is how a later run knows what is installed.

```json
{
  "schema": 1, "status": "ok", "exitStatus": 0, "code": null,
  "runId": "20260924-171207-8d53", "nodeVersion": "2.0.0", "nodeId": "c7fd…",
  "mode": "ip-pinned", "tls": "selfsigned", "publicHost": "203.0.113.10", "publicPort": 443, "domain": null,
  "bootstrapUrl": "wss://203.0.113.10/dht#pin-sha256=PS3w…Xl0",
  "relayUrl": "wss://203.0.113.10/relay#pin-sha256=PS3w…Xl0",
  "healthUrl": "https://203.0.113.10/healthz", "pin": "PS3w…Xl0", "certPem": "-----BEGIN CERTIFICATE-----\n…",
  "replacesUrls": [],
  "os": {"id": "ubuntu", "version": "24.04", "arch": "x86_64"}, "java": "21.0.4",
  "warnings": []
}
```

### 8.4 Files

| Path | Content |
|---|---|
| `/var/lib/vmessenger-installer/bundles/<version>/` | The uploaded bundle, root-owned; the two newest are kept. |
| `/var/lib/vmessenger-installer/runs/<run>/` | `args`, `log`, `result.json`, `exit`; the ten newest runs are kept. |
| `/etc/vmessenger/install.json` | The last successful run's result. |

### 8.5 What the installer fixes by itself, and what it asks first

Found before anything changes (preflight, which every run repeats):

| Code | Severity | What happens |
|---|---|---|
| `OS_UNSUPPORTED`, `ARCH_UNSUPPORTED`, `NO_SYSTEMD` | fatal (20) | Nothing is installed. |
| `RAM_TOO_LOW`, `DISK_LOW` | fatal (30) | Under 450 MB of memory; under ~1.5 GB free on `/var` (2.5 GB when a swapfile is needed), counting what `apt-get clean` would free. |
| `OS_EOL` | warn | Ubuntu 20.04, Debian 11. |
| `OS_UNTESTED` | consent | A release newer than the tested ones. |
| `CLOCK_SKEW` | consent | The app measured the server's clock more than 5 minutes off (`--clock-offset-ms`); allowed, the installer turns on time sync and, if that does not fix it, sets the clock. |
| `INSTALL_BUSY` | fatal (40) | Another install is running. |

Fixed on the way, reported as `info` (or `warn` where the result is worse than a clean install):

| Code | The problem | The fix |
|---|---|---|
| step `apt` `wait` | cloud-init is still running, or another package manager holds the lock | Wait, up to 15 minutes (`APT_LOCKED` after that). |
| `DPKG_INTERRUPTED` | An install was interrupted (a reboot mid-upgrade) | `dpkg --configure -a`, then `apt-get -f install` once the lists are fresh. |
| `APT_REPO_EXCLUDED` | A third-party repository no longer refreshes | Left out of this install's apt calls. |
| `APT_MIRROR_SWITCHED` | The release's mirror does not answer, or is stale | The fastest current mirror that answers from the server (below). |
| `APT_EOL_RELEASE` | The release has left the regular mirrors | `old-releases.ubuntu.com` or `archive.debian.org`. |
| `APT_SECURITY_GONE` (warn) | An end-of-life Debian's security suite lists packages no host serves (Debian 11 since September 2026) | Its security updates from snapshot.debian.org, as of the last date the suite was whole. |
| `APT_INDEX_STALE` | A mirror mid-sync is missing packages its index lists | Refresh and retry. |
| `APT_NETWORK_RETRY` | DNS or a download failed on a lossy link, or apt stalled | Retry, up to four times. An apt call that makes no progress — no bytes into apt's partial directories, no line in dpkg's log — for 2 minutes (`update`) or 5 (`install`) is stopped: on a stalled link apt's own timeouts do not always fire. A slow link that is getting there is left alone. HTTP pipelining is off (`Pipeline-Depth=0`). |
| `APT_FIXED_BROKEN` | Broken dependencies from an earlier half-done install | `apt-get -f install`, then retry. |
| `SWAP_ADDED` | Under 1 GB of memory and no swap | A 1 GB `/swapfile.vmessenger` (not in a container). |
| `DISK_CLEANED` | Tight disk | `apt-get clean`. |
| `JAVA_REUSED` | A JRE 17+ is already installed | Used as is. |

**The server's apt sources are never edited.** When a source has to be left out or replaced, the installer
writes its own list under `/var/lib/vmessenger-installer/apt/` and points only its own apt calls at it
(`-o Dir::Etc::SourceList`, `-o Dir::Etc::SourceParts`). Packages are verified against the distribution's keys
whichever mirror they come from.

Mirror candidates, probed from the server in parallel (each must serve this release and architecture, and its
`-updates` suite must be inside its `Valid-Until`; two of the Iranian mirrors below were weeks stale when this
was written, which is why the check exists):

- Ubuntu (amd64): archive.ubuntu.com, mirror.arvancloud.ir, mirror.iranserver.com, mirror.mobinhost.com,
  repo.iut.ac.ir, ir.archive.ubuntu.com. Ubuntu on ARM: ports.ubuntu.com only; none of the Iranian mirrors carry
  `ubuntu-ports`.
- Debian: deb.debian.org, mirror.arvancloud.ir, mirror.iranserver.com, mirror.mobinhost.com, repo.iut.ac.ir
  (amd64 only; its security suite comes from security.debian.org).

`--apt-mirror URL` names one outright.

### 8.6 Addresses, certificates and ports

| Mode | When | URLs |
|---|---|---|
| `ip-pinned` | No domain, or the domain does not point at this server (`DOMAIN_NOT_HERE`) | `wss://<public host>[:port]/…#pin-sha256=<pin>` |
| `domain-ca` | A domain, and Let's Encrypt issued a certificate | `wss://<domain>[:port]/…`, no pin |
| `domain-pinned` | A domain that points here, but no certificate (`ACME_UNREACHABLE`, `LE_FAILED`, `LE_RATE_LIMITED`, `LE_SKIPPED`) | `wss://<domain>[:port]/…#pin-sha256=<pin>` |

- **The certificate.** Self-signed, EC P-256, in `/etc/vmessenger/tls`. Its key is kept across runs, so the
  pin survives a renewed certificate or a changed address; the certificate is remade when it names another
  host or expires within 30 days. Before certbot, an HTTP probe checks that the domain reaches this server,
  so a domain pointing elsewhere does not spend Let's Encrypt's rate limit.
- **Ports.** `--public-port` (default 443) is what nginx serves TLS on and what every URL names. Who holds it
  is checked first: nginx is fine (and, for a bare IP, our site becomes the port's `default_server`); a stock
  apache2 is stopped only with `--allow PORT_APACHE`; anything else, or another site that is already the
  port's default, is `PUBLIC_PORT_TAKEN` (a decision, with a `fact free_port` suggestion). Port 80 in use
  means no plain HTTP (`HTTP_SKIPPED`); a taken local node port moves to the next free one. An active ufw
  gets the node's ports opened (`UFW_OPENED`); ufw is never turned on.
- **nginx is changed transactionally.** An already-invalid configuration stops the install
  (`NGINX_CONFIG_BROKEN`) before anything is touched; a new site nginx refuses is taken back out
  (`NGINX_NEW_CONFIG_FAILED`); other sites are left alone.
- **What the node advertises** is written to `/etc/vmessenger/node.managed.env` on every run
  (`VMESSENGER_PUBLIC_HOST`, the pinned `VMESSENGER_ADVERTISED_DHT_URL`); `node.env` still wins.
- **The install is atomic.** The new node goes to `/opt/vmessenger.new` and is swapped in; the previous one
  stays in `/opt/vmessenger.prev`, and a node that does not come up is rolled back (`HEALTH_LOCAL_FAILED`).
  Installing an older version over a newer one needs `--allow DOWNGRADE`. `node.seed` is never touched.
- **Health** is checked locally, then through nginx with the pin (`curl --pinnedpubkey`) or the CA, then
  `/relay` must upgrade (101), and the node must advertise the URL it will be reached at.
