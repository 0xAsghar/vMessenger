#!/usr/bin/env bash
#
# vMessenger node setup — production (nginx + systemd + TLS) or local dev (TCP DHT).
#
# From the repo:
#   sudo ./scripts/setup-node.sh --domain relay.example.com --acme-email you@example.com
#   sudo ./scripts/setup-node.sh                          # auto-detect public IP, self-signed cert
#   sudo ./scripts/setup-node.sh --dist-tar node/build/distributions/vmessenger-node-1.0.0.tar.gz
#   ./scripts/setup-node.sh --dev
#
# One-line install on Ubuntu/Debian (downloads the latest node tarball from GitHub Releases):
#   curl -fsSL https://raw.githubusercontent.com/0xAsghar/vMessenger/main/scripts/setup-node.sh | sudo bash -s --
#
# Machine mode (--from-app) is what the app's "New node" drives over SSH. It never touches GitHub:
# the app uploads this script, the templates and the node tarball as a bundle, and the install runs
# detached under systemd so a dropped connection cannot kill it. See docs/Deployment.md §7.
#   sudo bash setup-node.sh --from-app --bundle-dir DIR --preflight [install options]
#   sudo bash setup-node.sh --from-app --bundle-dir DIR --launch [install options]
#   sudo bash setup-node.sh --from-app --follow RUN [--from-byte N]
#
set -euo pipefail
set -E

readonly GITHUB_REPO="0xAsghar/vMessenger"
readonly DEFAULT_GIT_URL="https://github.com/${GITHUB_REPO}.git"
readonly RAW_BASE="https://raw.githubusercontent.com/${GITHUB_REPO}/main"
readonly RELEASE_API="https://api.github.com/repos/${GITHUB_REPO}/releases/latest"
readonly SETUP_SCRIPT_URL="${RAW_BASE}/scripts/setup-node.sh"
readonly DEFAULT_INSTALL_DIR="/opt/vmessenger"
readonly DEFAULT_NODE_USER="vmessenger"
readonly DEFAULT_NODE_PORT="8443"
readonly DEFAULT_TCP_PORT="46555"
readonly DEFAULT_CERT_DIR="/etc/vmessenger/tls"
readonly CERT_VALID_DAYS="825"
readonly STATE_DIR="/var/lib/vmessenger"
readonly ACME_WEBROOT="/var/www/acme"
readonly LE_LIVE_DIR="/etc/letsencrypt/live"
readonly REALIP_SNIPPET="/etc/nginx/snippets/vmessenger-realip.conf"
readonly NODE_ENV_FILE="/etc/vmessenger/node.env"
readonly MANAGED_ENV_FILE="/etc/vmessenger/node.managed.env"
readonly NGINX_SITE="/etc/nginx/sites-available/vmessenger-node.conf"
readonly SYSTEMD_UNIT="/etc/systemd/system/vmessenger-node.service"

# Machine mode. PROTOCOL_VERSION changes only when a marker, step id or issue code changes meaning;
# the app refuses a bundle whose protocol it does not speak.
readonly PROTOCOL_VERSION=1
readonly INSTALLER_HOME="/var/lib/vmessenger-installer"
readonly RUNS_DIR="$INSTALLER_HOME/runs"
readonly BUNDLES_DIR="$INSTALLER_HOME/bundles"
readonly LOCK_FILE="$INSTALLER_HOME/lock"
readonly INSTALL_RECORD="/etc/vmessenger/install.json"
readonly RUN_UNIT_PREFIX="vmessenger-install-"
readonly RUNS_KEPT=10
readonly SSH_HARDENING_FILE="/etc/ssh/sshd_config.d/00-vmessenger-hardening.conf"
readonly SSH_ROLLBACK_UNIT="vmessenger-ssh-rollback"
readonly F2B_JAIL="/etc/fail2ban/jail.d/vmessenger-sshd.local"
readonly UNATTENDED_CONF="/etc/apt/apt.conf.d/52vmessenger-unattended"
readonly APT_DIR="$INSTALLER_HOME/apt"

# Resources. Below MIN_RAM_MB the JVM and nginx do not fit; below SWAP_BELOW_RAM_MB a swapfile of
# SWAP_MB keeps apt and the JVM from being OOM-killed.
readonly MIN_RAM_MB=450
readonly SWAP_BELOW_RAM_MB=1000
readonly SWAP_MB=1024
readonly SWAP_FILE="/swapfile.vmessenger"
readonly MIN_DISK_MB=1500
readonly CLOCK_SKEW_MS=300000

# Debian releases whose security archive was wound down after end of life, and the last
# snapshot.debian.org timestamp at which it was whole. bullseye's LTS ended 2026-08-31; by late
# September its security index listed packages no host served, the latest snapshots included.
readonly EOL_SECURITY_SNAPSHOTS="bullseye|20260815T000000Z"

# Where apt can fetch from when the configured mirror cannot be reached. Each was checked to serve
# dists/<codename>/Release (Sept 2026); the installer probes them again from the server and uses
# the fastest that answers, for its own apt calls only. The server's sources are never edited.
readonly UBUNTU_MIRRORS="http://archive.ubuntu.com/ubuntu http://mirror.arvancloud.ir/ubuntu http://mirror.iranserver.com/ubuntu http://mirror.mobinhost.com/ubuntu http://repo.iut.ac.ir/repo/Ubuntu http://ir.archive.ubuntu.com/ubuntu"
readonly UBUNTU_PORTS_MIRRORS="http://ports.ubuntu.com/ubuntu-ports"
# debian mirror|security mirror
readonly DEBIAN_MIRRORS="http://deb.debian.org/debian|http://security.debian.org/debian-security http://mirror.arvancloud.ir/debian|http://mirror.arvancloud.ir/debian-security http://mirror.iranserver.com/debian|http://mirror.iranserver.com/debian-security http://mirror.mobinhost.com/debian|http://mirror.mobinhost.com/debian-security http://repo.iut.ac.ir/repo/debian|http://security.debian.org/debian-security"

MODE="prod"
DOMAIN=""
PUBLIC_IP=""
INSTALL_DIR="$DEFAULT_INSTALL_DIR"
NODE_USER="$DEFAULT_NODE_USER"
NODE_PORT="$DEFAULT_NODE_PORT"
CERT_DIR="$DEFAULT_CERT_DIR"
TLS_MODE=""            # letsencrypt | selfsigned (default: letsencrypt when --domain, else selfsigned)
ACME_EMAIL=""
ACME_NO_EMAIL=false
BEHIND_CDN="none"      # none | arvan
DIST_TAR=""
DIST_URL=""
BUILD_FROM_REPO=false
SKIP_BUILD=false       # legacy: reuse node/build/install/vmessenger-node from the repo
SKIP_CERT=false
FORCE_CERT=false
FIREWALL=false
GIT_URL="${VMESSENGER_GIT_URL:-$DEFAULT_GIT_URL}"
PUBLIC_NAME=""
SERVER_NAME=""
REPO_ROOT=""
TEMPLATE_DIR=""
DIST_SOURCE_DIR=""
TMP_DIRS=()
TLS_SUMMARY=""

ACTION="install"       # install | preflight | launch | run | follow | status | result | list-runs | version
FROM_APP=false         # machine mode: ##vm markers, bundle only, no prompts
OFFLINE=false          # never download anything but apt packages and certificates
BUNDLE_DIR=""
RUN_ID=""
RUN_DIR=""
FROM_BYTE=0
INSTALL_ARGS=()        # the install options as given; --launch hands them to the detached run
MARK_SEQ=0
SEQ_FILE=""            # the marker counter, when markers may come from subshells (runs)
CURRENT_STEP=""
FATAL_CODE=""
WARNINGS=()
OS_ID=""
OS_VERSION_ID=""
OS_CODENAME=""
OS_PRETTY=""
ARCH=""
NODE_ID=""
ALLOW=","              # consents given with --allow, as ,CODE,CODE,
PENDING_CONSENTS=()
CLOCK_OFFSET_MS=""     # phone clock minus server clock, measured by the app
APT_MIRROR=""          # --apt-mirror: use this mirror for the install's apt calls
APT_OPTS=(-o DPkg::Lock::Timeout=600 -o Acquire::Retries=3 -o Acquire::http::Timeout=30
    -o Acquire::http::Pipeline-Depth=0 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
APT_SOURCES_NOTE=""    # what the install's apt calls use, when not the server's own sources
JAVA_BIN=""
JAVA_HOME_DIR=""
RAM_MB=0
PUBLIC_PORT=443        # where nginx serves TLS, and the port in every URL
NO_HTTP=false          # no port-80 server (taken, or asked not to)
PIN=""                 # base64url SHA-256 of the served certificate's key, when the URLs are pinned
CERT_PEM_FILE=""       # the certificate the node serves
NODE_MODE=""           # ip-pinned | domain-ca | domain-pinned
URL_HOST=""            # the host every URL names
PREVIOUS_URLS=""       # the last install's relay/bootstrap URLs, when they change
SECURE=false           # --secure: fail2ban, automatic security updates, time sync
KEY_ONLY_SSH=false     # --key-only-ssh: password logins off, confirmed by a fresh key login
SSH_USER=""            # the account the app logs in as (key-only checks its keys)
SSH_CONFIRM_SECONDS=170
PURGE=false
HARDENING_F2B="off"
HARDENING_UPDATES="off"
HARDENING_TIME="off"
HARDENING_SSH="off"

usage() {
    cat <<'EOF'
Usage: setup-node.sh [options]

Production (requires root):
  --domain HOST         Public hostname (TLS SAN + advertised URLs). Enables Let's Encrypt by default.
  --ip ADDRESS          Public IP when no domain (default: auto-detect outbound IP)
  --public-host HOST    What clients dial when there is no domain: an IP, or a name (same as --ip)
  --public-port PORT    TLS port nginx serves and every URL names (default 443)
  --no-http             Serve nothing on port 80 (no Let's Encrypt HTTP-01, no redirect)
  --secure              Harden the server: fail2ban for SSH, automatic security updates, time sync
  --key-only-ssh        Turn password logins off (machine mode; rolled back unless a key login confirms)
  --ssh-user USER       The account that logs in (its authorized_keys must not be empty)
  --uninstall           Remove the node, its unit and its nginx site (--purge: its identity and state too)
  --tls MODE            letsencrypt | selfsigned (default: letsencrypt with --domain, else selfsigned)
  --acme-email EMAIL    Contact e-mail for Let's Encrypt expiry notices
  --acme-no-email       Register with Let's Encrypt without a contact e-mail
  --behind-cdn NAME     none | arvan — restore the real client IP from the CDN's X-Forwarded-For
  --firewall            Configure ufw (allow OpenSSH, 80, 443; deny other inbound)
  --dist-tar PATH       Install this vmessenger-node-<ver>.tar.gz (built with ./gradlew :node:distTar)
  --dist-url URL        Download the tarball from URL instead of the latest GitHub release
  --build               Build from the repo (needs JDK 21 + Android SDK; prefer a tarball on servers)
  --install-dir PATH    Install path (default: /opt/vmessenger)
  --cert-dir PATH       Self-signed certificate directory (default: /etc/vmessenger/tls)
  --node-port PORT      JVM listen port behind nginx (default: 8443)
  --skip-cert           Do not issue/generate certificates (they must already exist)
  --force-cert          Regenerate the self-signed certificate even if one exists
  --skip-build          Reuse node/build/install/vmessenger-node from the repo (legacy)
  --allow CODE[,CODE]   Go ahead where the installer would stop to ask (OS_UNTESTED, CLOCK_SKEW)
  --clock-offset-ms N   How far this server's clock is behind a trusted one (the app's), in ms
  --apt-mirror URL      Fetch packages from this Ubuntu/Debian mirror for this install

Local development:
  --dev                 Run the TCP DHT node on :46555 (no nginx/systemd)

Offline and machine mode (what the app uses; see docs/Deployment.md §7):
  --offline             Download nothing but apt packages and certificates
  --bundle-dir DIR      Templates and node tarball from an uploaded bundle (checked against SHA256SUMS)
  --from-app            Machine mode: ##vm progress markers, implies --offline
  --preflight           Report facts and problems without changing anything
  --launch              Start the install detached under systemd and print its run id
  --follow RUN          Stream a run's log (--from-byte N resumes); exits with the run's status
  --status RUN          One line: running / done / failed, exit status, log size
  --result RUN          The run's result.json
  --list-runs           Every run on this server, newest first
  --confirm-ssh RUN     A fresh key-only login works: keep the run's key-only SSH setting
  --version             Installer protocol and bundled node version

Environment:
  VMESSENGER_GIT_URL    Git clone URL when building outside a repo checkout
  VMESSENGER_REPO       Path to an existing vMessenger clone (skip clone)

Examples:
  sudo ./scripts/setup-node.sh --domain relay.example.com --acme-email ops@example.com --behind-cdn arvan --firewall
  sudo ./scripts/setup-node.sh --ip 203.0.113.10
  sudo ./scripts/setup-node.sh --domain relay.example.com --dist-tar /tmp/vmessenger-node-1.0.0.tar.gz
  ./scripts/setup-node.sh --dev
EOF
}

log() { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }

now_ms() { date +%s%3N; }

# Percent-encodes a marker value: everything but [A-Za-z0-9._~:/@+,-] becomes %XX, byte by byte,
# so values can hold spaces, '=', newlines and UTF-8 without breaking the one-line grammar.
pct() {
    local LC_ALL=C s="$1" out="" c i
    for ((i = 0; i < ${#s}; i++)); do
        c="${s:i:1}"
        case "$c" in
            [A-Za-z0-9._~:/@+,-]) out+="$c" ;;
            *) out+="$(printf '%%%02X' "'$c")" ;;
        esac
    done
    printf '%s' "$out"
}

# mark EVENT [key=value ...] — one line the app parses, in machine mode only:
#   ##vm v=1 seq=N ts=EPOCH_MS ev=EVENT key=value ...
# seq rises by one per line within an invocation, so a client that reconnects mid-run can drop what
# it has already seen. Written to fd 3, the script's own stdout (opened in main): a function whose
# output is captured with $(…) still reports to the app, instead of into its caller's variable.
mark() {
    [[ "$FROM_APP" == true ]] || return 0
    local ev="$1" kv line
    shift
    # A subshell's increments do not come back to the parent, so the counter lives in a file when
    # there is one to keep it in; seq stays gap-free and unique either way.
    if [[ -n "$SEQ_FILE" ]]; then
        MARK_SEQ=$(( $(cat "$SEQ_FILE" 2>/dev/null || printf 0) + 1 ))
        printf '%s' "$MARK_SEQ" > "$SEQ_FILE"
    else
        MARK_SEQ=$((MARK_SEQ + 1))
    fi
    line="##vm v=$PROTOCOL_VERSION seq=$MARK_SEQ ts=$(now_ms) ev=$ev"
    for kv in "$@"; do
        line+=" ${kv%%=*}=$(pct "${kv#*=}")"
    done
    printf '%s\n' "$line" >&3
}

fact() { mark fact key="$1" value="$2"; }

# step ID STATE [NOTE] — STATE is start | ok | skip | warn | fail | wait.
step() {
    CURRENT_STEP="$1"
    if [[ -n "${3:-}" ]]; then
        mark step id="$1" state="$2" note="$3"
    else
        mark step id="$1" state="$2"
    fi
}

# issue CODE SEVERITY DETAIL — something the run reports and carries on past (warn, info).
issue() {
    local code="$1" severity="$2"
    shift 2
    [[ "$severity" == warn ]] && WARNINGS+=("$code")
    mark issue code="$code" severity="$severity" step="${CURRENT_STEP:-none}" detail="$*"
    if [[ "$severity" == warn ]]; then warn "[$code] $*"; fi
}

# Exit status by class of problem, so a caller can tell "this server cannot run a node" (20) from
# "fix this and run again" (30) from "another install is running" (40) without parsing text:
#   0 done · 1 failed · 10 needs a decision · 20 unsupported · 30 precondition · 40 busy
exit_status_for() {
    case "$1" in
        OS_UNSUPPORTED|ARCH_UNSUPPORTED|NO_SYSTEMD) printf '20' ;;
        NOT_ROOT|RAM_TOO_LOW|DISK_LOW) printf '30' ;;
        INSTALL_BUSY) printf '40' ;;
        OS_UNTESTED|CLOCK_SKEW|PORT_APACHE|PUBLIC_PORT_TAKEN|DOWNGRADE) printf '10' ;;
        *) printf '1' ;;
    esac
}

allowed() { [[ "$ALLOW" == *",$1,"* ]]; }

# consent CODE DETAIL — a change the installer will only make when told to (--allow CODE). Given,
# it returns 0 and the caller goes ahead. Not given: a preflight records it and carries on (and
# exits 10 at the end); anything else stops here with exit 10.
consent() {
    local code="$1"
    shift
    if allowed "$code"; then
        issue "$code" info "going ahead as allowed: $*"
        return 0
    fi
    PENDING_CONSENTS+=("$code")
    mark issue code="$code" severity=consent step="${CURRENT_STEP:-none}" detail="$*"
    printf 'decision needed: [%s] %s — run again with --allow %s to go ahead\n' "$code" "$*" "$code" >&2
    if [[ "$ACTION" != preflight ]]; then
        FATAL_CODE="$code"
        [[ -n "$CURRENT_STEP" ]] && mark step id="$CURRENT_STEP" state=fail
        exit "$(exit_status_for "$code")"
    fi
    return 1
}

# die CODE MESSAGE — every fatal path names its issue code.
die() {
    local code="$1"
    shift
    FATAL_CODE="$code"
    mark issue code="$code" severity=fatal step="${CURRENT_STEP:-none}" detail="$*"
    [[ -n "$CURRENT_STEP" ]] && mark step id="$CURRENT_STEP" state=fail
    printf 'error: [%s] %s\n' "$code" "$*" >&2
    exit "$(exit_status_for "$code")"
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die CMD_MISSING "missing required command: $1"
}

# Keeps the exit status it was called with. Under `set -e` a trap that ends on a failed test would
# replace it — every die would exit 1, and a clean run with no temp dirs would too.
on_exit() {
    local status=$? dir
    # Returning a non-zero status from here would, with errtrace on, fire the ERR trap as well.
    trap - ERR
    for dir in "${TMP_DIRS[@]:-}"; do
        if [[ -n "$dir" && -d "$dir" ]]; then rm -rf "$dir"; fi
    done
    case "$ACTION" in
        run) finish_run "$status" ;;
        preflight|launch|status|list-runs|confirm-ssh|uninstall) mark end status="$status" ;;
    esac
    return "$status"
}

# A command that failed under `set -e` without going through die: report where, once, and exit 1 —
# not with the command's own status (apt's 100), which would read as one of ours. Command
# substitutions run in a subshell whose output is being captured, so only the top level speaks.
on_err() {
    local status=$? line="$1" command="$2"
    [[ -z "$FATAL_CODE" && "$BASH_SUBSHELL" -eq 0 ]] || return 0
    FATAL_CODE="INTERNAL"
    mark issue code=INTERNAL severity=fatal step="${CURRENT_STEP:-none}" detail="line $line: $command (exit $status)"
    [[ -n "$CURRENT_STEP" ]] && mark step id="$CURRENT_STEP" state=fail
    printf 'error: [INTERNAL] line %s: %s (exit %s)\n' "$line" "$command" "$status" >&2
    exit "$(exit_status_for INTERNAL)"
}

trap on_exit EXIT
trap 'on_err "$LINENO" "$BASH_COMMAND"' ERR

make_tmp_dir() {
    local dir
    dir="$(mktemp -d /tmp/vmessenger-setup.XXXXXX)"
    TMP_DIRS+=("$dir")
    printf '%s' "$dir"
}

# need_value FLAG COUNT — a flag that takes a value was given one.
need_value() {
    [[ "$2" -ge 2 ]] || die USAGE "$1 needs a value"
}

# Control flags choose what to do; install options say how, and are kept in INSTALL_ARGS so --launch
# can hand exactly the same ones to the detached run.
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --from-app) FROM_APP=true; OFFLINE=true; shift; continue ;;
            --offline) OFFLINE=true; shift; continue ;;
            --bundle-dir) need_value "$1" $#; BUNDLE_DIR="$2"; shift 2; continue ;;
            --preflight) ACTION=preflight; shift; continue ;;
            --launch) ACTION=launch; shift; continue ;;
            --run|--follow|--status|--result|--confirm-ssh)
                need_value "$1" $#
                ACTION="${1#--}"
                RUN_ID="$2"
                shift 2
                continue
                ;;
            --from-byte) need_value "$1" $#; FROM_BYTE="$2"; shift 2; continue ;;
            --list-runs) ACTION="list-runs"; shift; continue ;;
            --uninstall) ACTION=uninstall; shift; continue ;;
            --purge) PURGE=true; shift; continue ;;
            --version) ACTION=version; shift; continue ;;
            -h|--help) usage; exit 0 ;;
        esac
        local taken=2
        case "$1" in
            --domain|--ip|--public-host|--public-port|--tls|--acme-email|--behind-cdn|--dist-tar|--dist-url|--install-dir|--cert-dir|--node-port|--allow|--clock-offset-ms|--apt-mirror|--ssh-user|--ssh-confirm-seconds)
                need_value "$1" $#
                ;;
        esac
        case "$1" in
            --domain) DOMAIN="$2" ;;
            --ip|--public-host) PUBLIC_IP="$2" ;;
            --public-port) PUBLIC_PORT="$2" ;;
            --tls) TLS_MODE="$2" ;;
            --acme-email) ACME_EMAIL="$2" ;;
            --behind-cdn) BEHIND_CDN="$2" ;;
            --dist-tar) DIST_TAR="$2" ;;
            --dist-url) DIST_URL="$2" ;;
            --install-dir) INSTALL_DIR="$2" ;;
            --cert-dir) CERT_DIR="$2" ;;
            --node-port) NODE_PORT="$2" ;;
            --allow) ALLOW+="${2//[[:space:]]/},";;
            --clock-offset-ms) CLOCK_OFFSET_MS="$2" ;;
            --apt-mirror) APT_MIRROR="$2" ;;
            --acme-no-email) ACME_NO_EMAIL=true; taken=1 ;;
            --firewall) FIREWALL=true; taken=1 ;;
            --no-http) NO_HTTP=true; taken=1 ;;
            --secure) SECURE=true; taken=1 ;;
            --key-only-ssh) KEY_ONLY_SSH=true; taken=1 ;;
            --ssh-user) SSH_USER="$2" ;;
            --ssh-confirm-seconds) SSH_CONFIRM_SECONDS="$2" ;;
            --build) BUILD_FROM_REPO=true; taken=1 ;;
            --skip-cert) SKIP_CERT=true; taken=1 ;;
            --force-cert) FORCE_CERT=true; taken=1 ;;
            --skip-build) SKIP_BUILD=true; taken=1 ;;
            --dev) MODE="dev"; taken=1 ;;
            *) die USAGE "unknown argument: $1 (try --help)" ;;
        esac
        INSTALL_ARGS+=("${@:1:taken}")
        shift "$taken"
    done
    if [[ -n "$RUN_ID" && ! "$RUN_ID" =~ ^[0-9]{8}-[0-9]{6}-[0-9a-f]{4}$ ]]; then
        die USAGE "not a run id: $RUN_ID"
    fi
    [[ "$FROM_BYTE" =~ ^[0-9]+$ ]] || die USAGE "--from-byte takes a byte offset"
    [[ -z "$CLOCK_OFFSET_MS" || "$CLOCK_OFFSET_MS" =~ ^-?[0-9]+$ ]] || die USAGE "--clock-offset-ms takes milliseconds"
    [[ "$PUBLIC_PORT" =~ ^[0-9]+$ && "$PUBLIC_PORT" -ge 1 && "$PUBLIC_PORT" -le 65535 ]] || die USAGE "--public-port takes a port"
    # Hosts and domains end up in nginx config, unit files and URLs: letters, digits, '.', '-', ':'.
    [[ -z "$PUBLIC_IP" || "$PUBLIC_IP" =~ ^[A-Za-z0-9.:-]+$ ]] || die USAGE "--public-host is not a host name or address"
    [[ -z "$DOMAIN" || "$DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]] || die USAGE "--domain is not a domain name"
    [[ -z "$SSH_USER" || "$SSH_USER" =~ ^[a-z_][a-z0-9_.-]*$ ]] || die USAGE "--ssh-user is not a user name"
    [[ "$SSH_CONFIRM_SECONDS" =~ ^[0-9]+$ ]] || die USAGE "--ssh-confirm-seconds takes seconds"
    if [[ "$KEY_ONLY_SSH" == true && ( "$FROM_APP" == false || -z "$SSH_USER" ) ]]; then
        die USAGE "--key-only-ssh needs --from-app and --ssh-user: it waits for a confirmed key login"
    fi
    [[ -z "$APT_MIRROR" || "$APT_MIRROR" =~ ^https?://[A-Za-z0-9.:/_~-]+$ ]] || die USAGE "--apt-mirror takes an http(s) URL"
    if [[ "$FROM_APP" == true ]]; then
        case "$ACTION" in
            install) die USAGE "--from-app needs --preflight, --launch, --follow, --status or --result" ;;
            preflight|launch|run) [[ -n "$BUNDLE_DIR" ]] || die USAGE "--from-app --$ACTION needs --bundle-dir" ;;
        esac
        [[ "$MODE" == prod ]] || die USAGE "--dev is not a machine-mode install"
    fi
}

require_root_for_prod() {
    [[ "$ACTION" == version ]] && return 0
    if [[ "$MODE" == "prod" && "$(id -u)" -ne 0 ]]; then
        die NOT_ROOT "production setup must run as root (use sudo)"
    fi
}

detect_public_ip() {
    local ip=""
    if command -v ip >/dev/null 2>&1; then
        ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
    fi
    if [[ -z "$ip" ]] && command -v hostname >/dev/null 2>&1; then
        ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi
    [[ -n "$ip" ]] || return 1
    printf '%s' "$ip"
}

resolve_public_identity() {
    if [[ -n "$DOMAIN" ]]; then
        PUBLIC_NAME="$DOMAIN"
        SERVER_NAME="$DOMAIN"
        return
    fi
    if [[ -z "$PUBLIC_IP" ]]; then
        PUBLIC_IP="$(detect_public_ip)" || die PUBLIC_HOST_UNKNOWN "could not detect public IP — pass --ip or --domain"
        log "detected public IP: $PUBLIC_IP"
    fi
    PUBLIC_NAME="$PUBLIC_IP"
    SERVER_NAME="_"
}

# host[:port] as a URL spells it: IPv6 bracketed, the default port left out.
url_authority() {
    local host="$1"
    [[ "$host" == *:* ]] && host="[$host]"
    if [[ "$PUBLIC_PORT" == 443 ]]; then printf '%s' "$host"; else printf '%s:%s' "$host" "$PUBLIC_PORT"; fi
}

# node_url PATH — the URL clients use for PATH, with the key pin when the node's certificate is not
# one a CA vouches for (docs/Protocol.md §19).
node_url() {
    local url
    url="wss://$(url_authority "$URL_HOST")$1"
    if [[ -n "$PIN" ]]; then url+="#pin-sha256=$PIN"; fi
    printf '%s' "$url"
}

validate_args() {
    if [[ "$MODE" == "dev" ]]; then
        return
    fi
    resolve_public_identity
    if [[ -z "$TLS_MODE" ]]; then
        if [[ -n "$DOMAIN" ]]; then TLS_MODE="letsencrypt"; else TLS_MODE="selfsigned"; fi
    fi
    case "$TLS_MODE" in
        letsencrypt)
            [[ -n "$DOMAIN" ]] || die USAGE "--tls letsencrypt requires --domain"
            if [[ "$SKIP_CERT" == false && -z "$ACME_EMAIL" && "$ACME_NO_EMAIL" == false ]]; then
                die USAGE "--tls letsencrypt requires --acme-email EMAIL or --acme-no-email (or --skip-cert with certs already issued)"
            fi
            ;;
        selfsigned) ;;
        *) die USAGE "unknown --tls mode: $TLS_MODE (letsencrypt|selfsigned)" ;;
    esac
    case "$BEHIND_CDN" in
        none|arvan) ;;
        *) die USAGE "unknown --behind-cdn value: $BEHIND_CDN (none|arvan)" ;;
    esac
    if [[ -n "$DIST_TAR" && ! -f "$DIST_TAR" ]]; then
        die BUNDLE_MISSING "--dist-tar not found: $DIST_TAR"
    fi
}

find_repo_root() {
    if [[ -n "${VMESSENGER_REPO:-}" ]]; then
        REPO_ROOT="$(cd "$VMESSENGER_REPO" && pwd)"
        return
    fi
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || true)"
    if [[ -n "$script_dir" && -f "$script_dir/../gradlew" ]]; then
        REPO_ROOT="$(cd "$script_dir/.." && pwd)"
        return
    fi
    REPO_ROOT=""
}

clone_repo_if_needed() {
    if [[ -n "$REPO_ROOT" ]]; then
        log "using repo at $REPO_ROOT"
        return
    fi
    need_cmd git
    local tmp
    tmp="$(make_tmp_dir)"
    log "cloning $GIT_URL (shallow) into $tmp"
    git clone --depth 1 "$GIT_URL" "$tmp"
    REPO_ROOT="$tmp"
}

# Templates come from the bundle, else deploy/ in the repo; only the curl|bash path downloads them.
fetch_templates() {
    if [[ -n "$BUNDLE_DIR" ]]; then
        TEMPLATE_DIR="$BUNDLE_DIR/deploy"
        [[ -f "$TEMPLATE_DIR/nginx/vmessenger-node.conf.template" ]] || die BUNDLE_MISSING "no deploy/ templates in $BUNDLE_DIR"
        return
    fi
    if [[ -n "$REPO_ROOT" && -f "$REPO_ROOT/deploy/nginx/vmessenger-node.conf.template" ]]; then
        TEMPLATE_DIR="$REPO_ROOT/deploy"
        return
    fi
    [[ "$OFFLINE" == false ]] || die BUNDLE_MISSING "offline, and no templates: pass --bundle-dir or run from the repo"
    need_cmd curl
    TEMPLATE_DIR="$(make_tmp_dir)"
    log "downloading deploy templates from $RAW_BASE/deploy"
    install -d "$TEMPLATE_DIR/nginx" "$TEMPLATE_DIR/systemd"
    curl -fsSL "$RAW_BASE/deploy/nginx/vmessenger-node.conf.template" -o "$TEMPLATE_DIR/nginx/vmessenger-node.conf.template"
    curl -fsSL "$RAW_BASE/deploy/nginx/arvan-ips.conf" -o "$TEMPLATE_DIR/nginx/arvan-ips.conf"
    curl -fsSL "$RAW_BASE/deploy/systemd/vmessenger-node.service.template" -o "$TEMPLATE_DIR/systemd/vmessenger-node.service.template"
}

# ---- packages -------------------------------------------------------------------------------------

export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1 UCF_FORCE_CONFFOLD=1

apt_get() { apt-get "${APT_OPTS[@]}" "$@"; }

# apt_get_guarded STALL CAP ARGS… — apt-get, stopped (exit 124) when it has made no progress for
# STALL seconds, or after CAP seconds in all. On a stalled link apt's own timeouts do not always
# fire: a download that stops mid-file leaves gpgv waiting on it for good. A fixed limit cannot tell
# that from a slow link that is getting there, so progress is measured instead — bytes landing in
# apt's partial directories, lines landing in dpkg's log. Pipelining is off (Pipeline-Depth=0):
# middleboxes that mangle pipelined requests are a known cause of these stalls.
apt_get_guarded() {
    local stall="$1" cap="$2" pid mark last="" idle=0 elapsed=0
    shift 2
    apt-get "${APT_OPTS[@]}" "$@" &
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
        sleep 5
        elapsed=$((elapsed + 5))
        mark="$(apt_progress_mark)"
        if [[ "$mark" != "$last" ]]; then
            last="$mark"
            idle=0
        else
            idle=$((idle + 5))
        fi
        if [[ "$idle" -ge "$stall" || "$elapsed" -ge "$cap" ]]; then
            kill -TERM "$pid" 2>/dev/null || true
            sleep 10
            kill -KILL "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
            printf 'E: apt-get made no progress for %s s (%s s in all); stopped\n' "$idle" "$elapsed"
            return 124
        fi
    done
    wait "$pid"
}

# Changes whenever apt downloads or dpkg records a step.
apt_progress_mark() {
    du -sb /var/lib/apt/lists/partial /var/cache/apt/archives/partial 2>/dev/null | awk '{ s += $1 } END { printf "%d", s }'
    printf ':%s' "$(stat -c %s /var/log/dpkg.log 2>/dev/null || printf 0)"
}

# Another package manager holds the dpkg or lists lock (unattended-upgrades on a fresh server, a
# person in another shell): wait for it rather than fail, for up to 15 minutes.
wait_for_package_manager() {
    local waited=0
    if command -v cloud-init >/dev/null 2>&1 && cloud-init status 2>/dev/null | grep -q running; then
        step apt wait "cloud-init is still setting the server up"
        timeout 900 cloud-init status --wait >/dev/null 2>&1 || true
    fi
    while lslocks -n -o PATH 2>/dev/null | grep -Eq '^/var/lib/(dpkg/lock(-frontend)?|apt/lists/lock)$'; do
        if [[ "$waited" -eq 0 ]]; then
            step apt wait "another package manager is running (automatic updates?)"
            log "waiting for another package manager to finish"
        fi
        [[ "$waited" -lt 900 ]] || die APT_LOCKED "another package manager has held the dpkg lock for 15 minutes"
        sleep 5
        waited=$((waited + 5))
    done
    [[ "$waited" -eq 0 ]] || step apt start
}

# A dpkg run that was interrupted (a reboot mid-upgrade) blocks every later install until it is
# finished; finishing it is what apt itself tells you to do.
# When dpkg alone cannot finish (the package's dependencies never arrived), apt can, once the lists
# are fresh: see prepare_apt.
DPKG_NEEDS_APT=false
repair_dpkg() {
    [[ -n "$(dpkg --audit 2>/dev/null)" ]] || return 0
    issue DPKG_INTERRUPTED info "finishing an interrupted package installation"
    dpkg --configure -a >/dev/null 2>&1 || DPKG_NEEDS_APT=true
}

# The distribution's own suites, whatever mirror serves them: a PPA or a vendor repo is not base.
is_base_uri() {
    local uri="$1"
    [[ "$uri" != *launchpad* && "$uri" =~ /(ubuntu|Ubuntu|ubuntu-ports|debian|debian-security)/?$ ]]
}

# Every source file that mentions URI.
source_files_for() {
    grep -rlF -- "${1%/}" /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null || true
}

apt_proxy() { apt-config dump 2>/dev/null | sed -n 's/^Acquire::http::Proxy "\(.*\)";$/\1/p' | head -n 1; }

# Seconds to fetch a mirror's index for this release and architecture — or nothing when it does
# not answer, lacks the architecture, or is stale: a mirror whose -updates suite is past its
# Valid-Until answers fine and then fails apt with "Release file … is expired".
probe_mirror() {
    local uri="$1" arch proxy t="" valid_until
    arch="$(dpkg --print-architecture)"
    proxy="$(apt_proxy)"
    # Two tries: on a lossy link a live mirror can miss one.
    for _ in 1 2; do
        t="$(curl -fsS -L -o /dev/null -m 20 ${proxy:+-x "$proxy"} -w '%{time_total}' \
            "$uri/dists/$OS_CODENAME/main/binary-$arch/Release" 2>/dev/null)" && break
        t=""
    done
    [[ -n "$t" ]] || return 0
    valid_until="$(curl -fsS -L -m 20 ${proxy:+-x "$proxy"} "$uri/dists/$OS_CODENAME-updates/InRelease" 2>/dev/null \
        | sed -n 's/^Valid-Until: //p' | head -n 1)" || true
    if [[ -n "$valid_until" ]] && [[ "$(date -d "$valid_until" +%s 2>/dev/null || printf 0)" -lt "$(date +%s)" ]]; then
        printf 'stale'
        return 0
    fi
    printf '%s' "$t"
}

# Every mirror that answers from here and is current, fastest first, one "mirror|security" a line.
# Probed in parallel; a probe can take 40 s on a bad link.
rank_mirrors() {
    local candidates entry uri t n=0 dir
    if [[ "$OS_ID" == ubuntu ]]; then
        candidates="$UBUNTU_MIRRORS"
        [[ "$(dpkg --print-architecture)" == amd64 ]] || candidates="$UBUNTU_PORTS_MIRRORS"
    else
        candidates="$DEBIAN_MIRRORS"
    fi
    dir="$(make_tmp_dir)"
    for entry in $candidates; do
        n=$((n + 1))
        probe_mirror "${entry%%|*}" > "$dir/$n" &
    done
    wait
    n=0
    for entry in $candidates; do
        n=$((n + 1))
        uri="${entry%%|*}"
        t="$(cat "$dir/$n")"
        fact mirror_probe "$uri ${t:-unreachable}"
        if [[ -n "$t" && "$t" != stale ]]; then printf '%s %s\n' "$t" "$entry"; fi
    done | sort -n | awk '{ print $2 }'
}

# use_sources MIRROR [SECURITY] — the install's apt calls read only a list written here, naming
# MIRROR for this release; the server's own sources stay as they are.
use_sources() {
    local mirror="${1%/}" security="${2:-}" list="$APT_DIR/sources.list" components="main"
    install -d -m 0755 "$APT_DIR" "$APT_DIR/empty.d"
    if [[ "$OS_ID" == ubuntu ]]; then
        components="main universe"
        {
            printf 'deb %s %s %s\n' "$mirror" "$OS_CODENAME" "$components"
            printf 'deb %s %s-updates %s\n' "$mirror" "$OS_CODENAME" "$components"
            printf 'deb %s %s-security %s\n' "$mirror" "$OS_CODENAME" "$components"
        } > "$list"
    else
        {
            printf 'deb %s %s main\n' "$mirror" "$OS_CODENAME"
            printf 'deb %s %s-updates main\n' "$mirror" "$OS_CODENAME"
            case "$security" in
                "") ;;
                # snapshot.debian.org's copy is past its Valid-Until by design.
                *snapshot.debian.org*) printf 'deb [check-valid-until=no] %s %s-security main\n' "${security%/}" "$OS_CODENAME" ;;
                *) printf 'deb %s %s-security main\n' "${security%/}" "$OS_CODENAME" ;;
            esac
        } > "$list"
    fi
    APT_OPTS+=(-o "Dir::Etc::SourceList=$list" -o "Dir::Etc::SourceParts=$APT_DIR/empty.d")
    APT_SOURCES_NOTE="$mirror"
    fact apt_sources "$mirror"
}

# exclude_sources FILE... — the install's apt calls read every source file but these.
exclude_sources() {
    local parts="$APT_DIR/parts.d" f skip x
    install -d -m 0755 "$APT_DIR"
    rm -rf "$parts"
    install -d -m 0755 "$parts"
    for f in /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
        [[ -f "$f" ]] || continue
        skip=false
        for x in "$@"; do [[ "$f" == "$x" ]] && skip=true; done
        [[ "$skip" == true ]] || cp "$f" "$parts/"
    done
    local list=/etc/apt/sources.list
    for x in "$@"; do
        if [[ "$x" == /etc/apt/sources.list ]]; then list="$APT_DIR/empty.list"; : > "$list"; fi
    done
    APT_OPTS+=(-o "Dir::Etc::SourceParts=$parts" -o "Dir::Etc::SourceList=$list")
}

# snapshot.debian.org's copy of this release's security suite, from when it was whole.
eol_security_snapshot() {
    local entry stamp=""
    for entry in $EOL_SECURITY_SNAPSHOTS; do
        [[ "${entry%%|*}" == "$OS_CODENAME" ]] && stamp="${entry#*|}"
    done
    printf 'http://snapshot.debian.org/archive/debian-security/%s' "${stamp:-$(date -u +%Y%m%dT%H%M%SZ)}"
}

# Where the release lives once it is end-of-life and its mirrors have dropped it.
archive_mirror() {
    if [[ "$OS_ID" == ubuntu ]]; then
        printf 'http://old-releases.ubuntu.com/ubuntu'
    else
        printf 'http://archive.debian.org/debian|http://archive.debian.org/debian-security'
    fi
}

# apt-get update, until every source refreshes. What goes wrong is fixed for this install only:
#   a third-party source fails  -> left out of the install's apt calls
#   the release's mirror fails  -> the fastest mirror that answers, or the archive once it is EOL
#   "not valid yet"             -> the server's clock (needs consent)
apt_update() {
    local out="$APT_DIR/update.log" attempt failing base_fail="" third=() uri excluded=false switched=false
    local mirrors=() ranked=false entry f
    install -d -m 0755 "$APT_DIR"
    if [[ -n "$APT_MIRROR" ]]; then
        use_sources "$APT_MIRROR"
        switched=true
    fi
    for attempt in 1 2 3 4 5 6 7 8; do
        wait_for_package_manager
        apt_get_guarded 120 1800 update > "$out" 2>&1 || true
        failing="$(sed -n -E \
            -e 's/^Err:[0-9]+ ([^ ]+) .*/\1/p' \
            -e "s/^E: The repository '([^ ]+) .*/\1/p" \
            -e 's/^W: GPG error: ([^ ]+) .*/\1/p' \
            -e 's/^[WE]: Failed to fetch ([^ ]+)\/dists\/.*/\1/p' \
            -e 's/^E: Release file for ([^ ]+)\/dists\/.* is expired.*/\1/p' "$out" | sort -u)"
        if [[ -z "$failing" ]] && ! grep -Eq '^(E:|W: (Some index files|Failed to fetch))' "$out"; then
            return 0
        fi
        if grep -q 'not valid yet' "$out"; then
            fix_clock "apt says the package lists are not valid yet"
            continue
        fi
        base_fail=""
        third=()
        for uri in $failing; do
            if is_base_uri "$uri"; then
                base_fail="$uri"
            else
                while read -r f; do [[ -n "$f" ]] && third+=("$f"); done < <(source_files_for "$uri")
            fi
        done
        if [[ "${#third[@]}" -gt 0 && "$excluded" == false && "$switched" == false ]]; then
            issue APT_REPO_EXCLUDED info "left out of this install, because apt could not refresh them: ${third[*]}"
            exclude_sources "${third[@]}"
            excluded=true
            continue
        fi
        if [[ -n "$base_fail" && "$switched" == false ]]; then
            if grep -Eq "404 +Not Found|does not have a Release file" "$out"; then
                local archive
                archive="$(archive_mirror)"
                issue APT_EOL_RELEASE info "$OS_PRETTY has left the regular mirrors; using ${archive%%|*} for this install"
                use_sources "${archive%%|*}" "${archive#*|}"
                switched=true
                continue
            fi
        fi
        # The release's mirror — or the one this install switched to — still fails: the next
        # current mirror that answers, fastest first.
        if [[ -n "$base_fail" && ( "$attempt" -ge 2 || "$switched" == true ) ]]; then
            if [[ "$ranked" == false ]]; then
                while read -r entry; do [[ -n "$entry" ]] && mirrors+=("$entry"); done < <(rank_mirrors)
                ranked=true
            fi
            [[ "${#mirrors[@]}" -gt 0 ]] \
                || die APT_MIRROR_UNREACHABLE "no current package mirror answers from this server: $(grep -E '^(E|W):' "$out" | head -n 2 | tr '\n' ' ')"
            local mirror="${mirrors[0]}"
            mirrors=("${mirrors[@]:1}")
            issue APT_MIRROR_SWITCHED info "$base_fail does not work from here; using ${mirror%%|*} for this install"
            use_sources "${mirror%%|*}" "${mirror#*|}"
            switched=true
            continue
        fi
        issue APT_NETWORK_RETRY info "apt-get update failed (try $attempt): $(grep -E '^(E|W):' "$out" | head -n 1)"
        sleep $((attempt * 5))
    done
    die APT_UPDATE_FAILED "apt-get update keeps failing: $(grep -E '^(E|W):' "$out" | head -n 3 | tr '\n' ' ')"
}

# fix_clock REASON — with consent, turn on time sync and, if the clock is still off, set it from
# the offset the app measured.
fix_clock() {
    consent CLOCK_SKEW "the server's clock is wrong ($1): turn on time sync and correct it" || return 0
    timedatectl set-ntp true >/dev/null 2>&1 || true
    local i
    for i in $(seq 1 20); do
        [[ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null)" == yes ]] && break
        sleep 1
    done
    if [[ -n "$CLOCK_OFFSET_MS" && "${CLOCK_OFFSET_MS#-}" -gt "$CLOCK_SKEW_MS" ]]; then
        date -u -s "@$(( $(date +%s) + CLOCK_OFFSET_MS / 1000 ))" >/dev/null
        CLOCK_OFFSET_MS=0
        issue CLOCK_FIXED info "clock set to $(date -u +%FT%TZ)"
    fi
}

apt_install() {
    local out="$APT_DIR/install.log" try status
    for try in 1 2 3 4; do
        wait_for_package_manager
        status=0
        apt_get_guarded 300 5400 install -y -q --no-install-recommends "$@" > "$out" 2>&1 || status=$?
        cat "$out"
        [[ "$status" -ne 0 ]] || return 0
        # A lossy link (DNS that times out, a dropped download) fails an install that works a
        # minute later; apt resumes what it already fetched.
        grep -Eq 'Temporary failure resolving|Could not connect|Connection timed out|Connection failed|Hash Sum mismatch|Undetermined Error|made no progress' "$out" \
            || break
        issue APT_NETWORK_RETRY info "download failed (try $try of 4); retrying"
        sleep $((try * 10))
    done
    # Broken dependencies left by an earlier half-done install: let apt repair them, then retry.
    if grep -Eq 'Unmet dependencies|held broken packages|apt --fix-broken install' "$out"; then
        issue APT_FIXED_BROKEN info "repairing broken dependencies (apt-get -f install)"
        apt_get install -y -f -q >/dev/null 2>&1 || true
        apt_get install -y -q --no-install-recommends "$@" && return 0
    fi
    # The index lists packages the pool no longer has. A mirror mid-sync: refresh and retry. A
    # security suite being wound down after end of life (Debian 11 in 2026): its index outlives its
    # packages everywhere, and a server with its updates installed cannot do without them (a newer
    # ca-certificates breaks the release's ca-certificates-java). snapshot.debian.org, Debian's
    # archive of everything it ever published, still has them.
    if grep -Eq '^E: Failed to fetch .* 404 +Not Found' "$out"; then
        if grep -Eq '^E: Failed to fetch [^ ]*(-security|debian-security)/pool/' "$out" && [[ "$OS_ID" == debian ]]; then
            local mirror snapshot
            mirror="$(rank_mirrors)"
            mirror="${mirror%%$'\n'*}"
            [[ -n "$mirror" ]] || die APT_INSTALL_FAILED "the security archive's packages are gone and no mirror answers"
            snapshot="$(eol_security_snapshot)"
            issue APT_SECURITY_GONE warn "$OS_PRETTY's security archive no longer serves its packages; installing its security updates from snapshot.debian.org"
            use_sources "${mirror%%|*}" "$snapshot"
        else
            issue APT_INDEX_STALE info "the mirror is missing packages its index lists; refreshing"
        fi
        apt_update
        apt_get install -y -q --no-install-recommends "$@" && return 0
    fi
    die APT_INSTALL_FAILED "could not install $*: $(grep -E '^E:' "$out" | head -n 2 | tr '\n' ' ')"
}

prepare_apt() {
    need_cmd apt-get
    wait_for_package_manager
    repair_dpkg
    apt_update
    if [[ "$DPKG_NEEDS_APT" == true ]]; then
        wait_for_package_manager
        apt_get install -y -f -q >/dev/null 2>&1 || true
        [[ -z "$(dpkg --audit 2>/dev/null)" ]] \
            || die APT_INSTALL_FAILED "an interrupted package installation cannot be finished: $(dpkg --audit | head -n 2 | tr '\n' ' ')"
    fi
}

java_major() {
    "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p'
}

apt_has_candidate() {
    local candidate
    candidate="$(apt-cache "${APT_OPTS[@]}" policy "$1" 2>/dev/null | awk '/Candidate:/ { print $2 }')"
    [[ -n "$candidate" && "$candidate" != "(none)" ]]
}

# A JRE 17 or newer: one already installed if there is one, else the first of 21, 25, 17 that apt
# offers here (Debian 12 has 17 only; Ubuntu 20.04 through 24.04 have 21).
install_java() {
    local j major best="" best_major=0
    for j in /usr/lib/jvm/*/bin/java; do
        [[ -x "$j" ]] || continue
        major="$(java_major "$j")"
        [[ -n "$major" && "$major" -ge 17 && "$major" -gt "$best_major" ]] || continue
        best="$j"
        best_major="$major"
    done
    if [[ -z "$best" ]]; then
        local pkg chosen=""
        for pkg in openjdk-21-jre-headless openjdk-25-jre-headless openjdk-17-jre-headless; do
            if apt_has_candidate "$pkg"; then chosen="$pkg"; break; fi
        done
        [[ -n "$chosen" ]] || die JAVA_UNAVAILABLE "apt offers no OpenJDK 17, 21 or 25 on $OS_PRETTY"
        log "installing $chosen"
        apt_install "$chosen"
        for j in /usr/lib/jvm/*/bin/java; do
            [[ -x "$j" ]] || continue
            major="$(java_major "$j")"
            if [[ -n "$major" && "$major" -ge 17 && "$major" -gt "$best_major" ]]; then best="$j"; best_major="$major"; fi
        done
        [[ -n "$best" ]] || die JAVA_UNAVAILABLE "$chosen installed, but no java 17+ under /usr/lib/jvm"
    else
        issue JAVA_REUSED info "using the Java $best_major already installed"
    fi
    JAVA_BIN="$(readlink -f "$best")"
    JAVA_HOME_DIR="$(dirname "$(dirname "$JAVA_BIN")")"
    fact java "$best_major $JAVA_HOME_DIR"
}

install_os_packages() {
    local pkgs=(nginx ca-certificates rsync curl openssl)
    [[ "$TLS_MODE" == "letsencrypt" ]] && pkgs+=(certbot)
    [[ "$FIREWALL" == true ]] && pkgs+=(ufw)
    [[ "$FROM_APP" == true ]] || pkgs+=(qrencode)
    [[ "$BUILD_FROM_REPO" == true ]] && pkgs+=(openjdk-21-jdk-headless git)
    log "installing OS packages: ${pkgs[*]}"
    apt_install "${pkgs[@]}"
}

# Under SWAP_BELOW_RAM_MB of memory and no swap to speak of, a swapfile: apt and the JVM starting
# together are what an OOM kill on a small VPS looks like. Not in a container, which cannot swapon.
ensure_swap() {
    local swap_mb
    swap_mb="$(awk '/^SwapTotal:/ { print int($2 / 1024) }' /proc/meminfo)"
    if [[ "$RAM_MB" -ge "$SWAP_BELOW_RAM_MB" || "$swap_mb" -ge 512 ]]; then
        step swap skip
        return 0
    fi
    if systemd-detect-virt -cq 2>/dev/null; then
        issue SWAP_SKIPPED info "a container cannot add swap"
        step swap skip
        return 0
    fi
    if [[ ! -f "$SWAP_FILE" ]]; then
        fallocate -l "${SWAP_MB}M" "$SWAP_FILE" 2>/dev/null || dd if=/dev/zero of="$SWAP_FILE" bs=1M count="$SWAP_MB" status=none
        chmod 0600 "$SWAP_FILE"
        mkswap "$SWAP_FILE" >/dev/null
    fi
    swapon "$SWAP_FILE" 2>/dev/null || true
    grep -q "^$SWAP_FILE " /etc/fstab || printf '%s none swap sw 0 0\n' "$SWAP_FILE" >> /etc/fstab
    issue SWAP_ADDED info "added a ${SWAP_MB} MB swapfile ($RAM_MB MB of memory)"
    step swap ok
}

# The JVM heap for this much memory: a quarter of it, between 128 and 768 MB.
heap_mb() {
    local heap=$((RAM_MB / 4))
    [[ "$heap" -ge 128 ]] || heap=128
    [[ "$heap" -le 768 ]] || heap=768
    printf '%s' "$heap"
}

# ---- preflight ------------------------------------------------------------------------------------

# supported | eol | untested | unsupported, for this OS release.
os_support() {
    local v="$OS_VERSION_ID"
    case "$OS_ID" in
        ubuntu)
            case "$v" in
                22.04|24.04|26.04) printf 'supported' ;;
                20.04) printf 'eol' ;;
                *)
                    if [[ "$v" =~ ^[0-9]+\.[0-9]+$ ]] && [[ "${v%%.*}" -gt 20 || ( "${v%%.*}" -eq 20 && "${v#*.}" -gt 4 ) ]]; then
                        printf 'untested'
                    else
                        printf 'unsupported'
                    fi
                    ;;
            esac
            ;;
        debian)
            case "$v" in
                12|13) printf 'supported' ;;
                11) printf 'eol' ;;
                "") printf 'untested' ;;   # testing and unstable carry no VERSION_ID
                *) if [[ "$v" =~ ^[0-9]+$ && "$v" -gt 13 ]]; then printf 'untested'; else printf 'unsupported'; fi ;;
            esac
            ;;
        *) printf 'unsupported' ;;
    esac
}

free_mb() { df -Pm "$1" 2>/dev/null | awk 'NR == 2 { print $4 }'; }

# What stops an install, found before anything changes. Fatal problems stop here; decisions are
# asked (consent); warnings are reported and the install goes on.
preflight_checks() {
    read_os_release
    fact os_id "$OS_ID"
    fact os_version "$OS_VERSION_ID"
    fact os_codename "$OS_CODENAME"
    fact os_pretty "$OS_PRETTY"
    fact arch "$ARCH"
    fact server_time_ms "$(now_ms)"
    local support
    support="$(os_support)"
    fact os_support "$support"
    case "$support" in
        supported) ;;
        eol) issue OS_EOL warn "$OS_PRETTY no longer gets regular security updates; the node will run, but plan a move to a newer release" ;;
        untested) consent OS_UNTESTED "$OS_PRETTY is newer than the releases this installer was tested on (Ubuntu 20.04–26.04, Debian 11–13)" || true ;;
        *) die OS_UNSUPPORTED "${OS_PRETTY:-this system} is not supported: use Ubuntu 20.04–26.04 or Debian 11–13" ;;
    esac
    case "$ARCH" in
        x86_64|aarch64) ;;
        *) die ARCH_UNSUPPORTED "$ARCH is not supported: a node needs a 64-bit x86 or ARM server" ;;
    esac
    [[ -d /run/systemd/system ]] || die NO_SYSTEMD "systemd is not running as PID 1 (a container or an init-less VPS?)"
    RAM_MB="$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)"
    fact ram_mb "$RAM_MB"
    [[ "$RAM_MB" -ge "$MIN_RAM_MB" ]] || die RAM_TOO_LOW "$RAM_MB MB of memory; a node needs at least $MIN_RAM_MB MB"
    fact container "$(systemd-detect-virt -c 2>/dev/null || printf 'none')"
    local need="$MIN_DISK_MB" free cache
    [[ "$RAM_MB" -ge "$SWAP_BELOW_RAM_MB" ]] || need=$((need + SWAP_MB))
    free="$(free_mb /var)"
    cache="$(du -sm /var/cache/apt/archives 2>/dev/null | awk '{ print $1 }')"
    fact disk_free_mb "$free"
    if [[ "$((free + ${cache:-0}))" -lt "$need" ]]; then
        die DISK_LOW "${free} MB free on /var; the install needs about $need MB"
    fi
    if [[ -n "$CLOCK_OFFSET_MS" && "${CLOCK_OFFSET_MS#-}" -gt "$CLOCK_SKEW_MS" ]]; then
        fact clock_offset_ms "$CLOCK_OFFSET_MS"
        consent CLOCK_SKEW "the server's clock is $((${CLOCK_OFFSET_MS#-} / 60000)) minutes off, which breaks TLS and apt: turn on time sync and correct it" || true
    fi
    local j
    for j in /usr/lib/jvm/*/bin/java; do
        [[ -x "$j" ]] && fact java_installed "$(java_major "$j") $j"
    done
    if [[ -f "$INSTALL_RECORD" ]]; then
        fact installed_version "$(sed -n 's/.*"nodeVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$INSTALL_RECORD" | head -n 1)"
    fi
    local active
    if active="$(active_run)" && [[ "$active" != "$RUN_ID" ]]; then
        fact active_run "$active"
        die INSTALL_BUSY "install $active is still running"
    fi
    if [[ "$ACTION" == preflight ]]; then
        check_ports
    fi
    if [[ -f "$INSTALL_RECORD" ]]; then
        PREVIOUS_URLS="$(sed -n 's/.*"\(relayUrl\|bootstrapUrl\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p' "$INSTALL_RECORD" | tr '\n' ' ')"
    fi
}

# Frees what apt can give back when the disk is tight.
make_disk_room() {
    local free
    free="$(free_mb /var)"
    if [[ "$free" -lt "$MIN_DISK_MB" ]]; then
        apt-get clean
        issue DISK_CLEANED info "cleared apt's package cache (${free} MB was free)"
    fi
}

configure_firewall() {
    [[ "$FIREWALL" == true ]] || return 0
    need_cmd ufw
    log "configuring ufw (OpenSSH, 80/tcp, 443/tcp)"
    ufw default deny incoming >/dev/null
    ufw default allow outgoing >/dev/null
    ufw allow OpenSSH >/dev/null
    ufw allow 80/tcp >/dev/null
    ufw allow 443/tcp >/dev/null
    ufw --force enable >/dev/null
}

build_node() {
    [[ -n "$REPO_ROOT" ]] || die USAGE "no repo root for build"
    log "building node (:node:installDist) — requires JDK 21 and an Android SDK for Gradle configuration"
    cd "$REPO_ROOT"
    chmod +x ./gradlew
    ./gradlew :node:installDist -q --no-daemon
    cd - >/dev/null
}

extract_tarball() {
    local tar_path="$1"
    local out
    out="$(make_tmp_dir)"
    log "extracting $(basename "$tar_path")"
    tar -xzf "$tar_path" --strip-components=1 -C "$out"
    [[ -x "$out/bin/node" ]] || die BUNDLE_CORRUPT "tarball does not contain bin/node: $tar_path"
    DIST_SOURCE_DIR="$out"
}

download_dist() {
    local url="$1"
    need_cmd curl
    local tmp
    tmp="$(make_tmp_dir)"
    log "downloading $url"
    curl -fL --retry 3 -o "$tmp/node.tar.gz" "$url"
    extract_tarball "$tmp/node.tar.gz"
}

latest_release_dist_url() {
    curl -fsSL "$RELEASE_API" 2>/dev/null \
        | grep -o '"browser_download_url": *"[^"]*vmessenger-node-[^"]*\.tar\.gz"' \
        | head -1 \
        | sed -E 's/.*"(https:[^"]+)"/\1/'
}

# Precedence: --dist-tar > the bundle's tarball > --dist-url > --build > repo installDist
# (--skip-build) > latest GitHub release tarball. Offline stops before anything that downloads.
acquire_dist() {
    if [[ -n "$DIST_TAR" ]]; then
        extract_tarball "$DIST_TAR"
        return
    fi
    if [[ -n "$BUNDLE_DIR" ]]; then
        extract_tarball "$(bundle_tarball)"
        return
    fi
    [[ "$OFFLINE" == false || "$SKIP_BUILD" == true ]] \
        || die BUNDLE_MISSING "offline, and no node tarball: pass --dist-tar or --bundle-dir"
    if [[ "$SKIP_BUILD" == true && "$OFFLINE" == true ]]; then
        [[ -n "$REPO_ROOT" && -x "$REPO_ROOT/node/build/install/vmessenger-node/bin/node" ]] \
            || die BUNDLE_MISSING "offline, and no build at node/build/install/vmessenger-node"
    fi
    if [[ -n "$DIST_URL" ]]; then
        download_dist "$DIST_URL"
        return
    fi
    if [[ "$BUILD_FROM_REPO" == true ]]; then
        clone_repo_if_needed
        build_node
        DIST_SOURCE_DIR="$REPO_ROOT/node/build/install/vmessenger-node"
        return
    fi
    if [[ "$SKIP_BUILD" == true && -n "$REPO_ROOT" && -x "$REPO_ROOT/node/build/install/vmessenger-node/bin/node" ]]; then
        log "using existing build at $REPO_ROOT/node/build/install/vmessenger-node"
        DIST_SOURCE_DIR="$REPO_ROOT/node/build/install/vmessenger-node"
        return
    fi
    local url
    url="$(latest_release_dist_url || true)"
    if [[ -z "$url" ]]; then
        die BUNDLE_MISSING "no vmessenger-node tarball in the latest GitHub release — pass --dist-tar (./gradlew :node:distTar) or --build"
    fi
    download_dist "$url"
}

create_system_user() {
    if ! id "$NODE_USER" &>/dev/null; then
        log "creating system user $NODE_USER"
        useradd --system --home "$INSTALL_DIR" --shell /usr/sbin/nologin "$NODE_USER"
    fi
}

create_dirs() {
    install -d -o "$NODE_USER" -g "$NODE_USER" "$INSTALL_DIR" "$STATE_DIR" "$STATE_DIR/tmp"
    install -d -m 0755 "$ACME_WEBROOT" /etc/vmessenger /etc/nginx/snippets
}

# The node lands in INSTALL_DIR.new and is swapped in whole; the previous one stays as
# INSTALL_DIR.prev, which a failed health check rolls back to. node.seed (the node's identity)
# lives in STATE_DIR and is never touched.
install_node_files() {
    [[ -x "$DIST_SOURCE_DIR/bin/node" ]] || die BUNDLE_MISSING "node distribution not found at $DIST_SOURCE_DIR"
    check_downgrade
    log "installing node to $INSTALL_DIR"
    rm -rf "${INSTALL_DIR}.new"
    rsync -a --delete "$DIST_SOURCE_DIR/" "${INSTALL_DIR}.new/"
    chown -R "$NODE_USER:$NODE_USER" "${INSTALL_DIR}.new"
    if [[ -d "$INSTALL_DIR" && -x "$INSTALL_DIR/bin/node" ]]; then
        rm -rf "${INSTALL_DIR}.prev"
        mv "$INSTALL_DIR" "${INSTALL_DIR}.prev"
    else
        rm -rf "$INSTALL_DIR"
    fi
    mv "${INSTALL_DIR}.new" "$INSTALL_DIR"
}

installed_version() {
    if [[ -f "$INSTALL_DIR/VERSION" ]]; then
        tr -d '[:space:]' < "$INSTALL_DIR/VERSION"
    elif [[ -f "$INSTALL_RECORD" ]]; then
        sed -n 's/.*"nodeVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$INSTALL_RECORD" | head -n 1
    fi
}

# Replacing a newer node with an older one needs a go-ahead.
check_downgrade() {
    local installed new
    installed="$(installed_version)"
    new="$(tr -d '[:space:]' < "$DIST_SOURCE_DIR/VERSION" 2>/dev/null || true)"
    [[ -n "$installed" && -n "$new" && "$installed" != "$new" ]] || return 0
    if [[ "$(printf '%s\n%s\n' "$installed" "$new" | sort -V | tail -n 1)" == "$installed" ]]; then
        consent DOWNGRADE "this server runs node $installed; installing $new would downgrade it" || true
    fi
}

rollback_node() {
    [[ -d "${INSTALL_DIR}.prev" ]] || return 1
    log "rolling back to the previous node"
    rm -rf "$INSTALL_DIR"
    mv "${INSTALL_DIR}.prev" "$INSTALL_DIR"
    systemctl restart vmessenger-node || true
}

# Earlier deploy docs installed a differently named unit and site; both would
# fight over 127.0.0.1:8443 and :443 if left in place.
remove_legacy_artifacts() {
    if systemctl list-unit-files vmessenger-relay.service >/dev/null 2>&1 \
        && [[ -f /etc/systemd/system/vmessenger-relay.service ]]; then
        log "removing legacy vmessenger-relay.service"
        systemctl disable --now vmessenger-relay >/dev/null 2>&1 || true
        rm -f /etc/systemd/system/vmessenger-relay.service
    fi
    if [[ -e /etc/nginx/sites-enabled/relay.vmessenger.ir.conf || -e /etc/nginx/sites-available/relay.vmessenger.ir.conf ]]; then
        log "removing legacy nginx site relay.vmessenger.ir.conf"
        rm -f /etc/nginx/sites-enabled/relay.vmessenger.ir.conf /etc/nginx/sites-available/relay.vmessenger.ir.conf
    fi
}

write_node_env() {
    [[ -f "$NODE_ENV_FILE" ]] && return 0
    log "writing $NODE_ENV_FILE (operator overrides; all optional)"
    cat > "$NODE_ENV_FILE" <<'EOF'
# vMessenger node — optional overrides read by vmessenger-node.service.
# Uncomment to change a default. Log level: -Dorg.slf4j.simpleLogger.defaultLogLevel=debug via JAVA_OPTS.
#VMESSENGER_ADVERTISED_DHT_URL=wss://relay.example.com/dht
#VMESSENGER_PEER_NODES=wss://other-relay.example.com/dht
#VMESSENGER_MAX_LISTENERS=20000
#VMESSENGER_PROOF_MAX_SKEW_MS=300000
EOF
    chmod 0644 "$NODE_ENV_FILE"
}

write_systemd_unit() {
    local template="$TEMPLATE_DIR/systemd/vmessenger-node.service.template"
    [[ -f "$template" ]] || die BUNDLE_MISSING "systemd template not found at $template"
    log "writing $SYSTEMD_UNIT"
    sed -e "s|__INSTALL_DIR__|${INSTALL_DIR}|g" \
        -e "s|__NODE_PORT__|${NODE_PORT}|g" \
        -e "s|__PUBLIC_NAME__|${PUBLIC_NAME}|g" \
        -e "s|__JAVA_HOME__|${JAVA_HOME_DIR}|g" \
        -e "s|__HEAP_MB__|$(heap_mb)|g" \
        "$template" > "$SYSTEMD_UNIT"
    if [[ "$NODE_USER" != "$DEFAULT_NODE_USER" ]]; then
        sed -i -e "s|^User=.*|User=${NODE_USER}|" -e "s|^Group=.*|Group=${NODE_USER}|" "$SYSTEMD_UNIT"
    fi
}

write_realip_snippet() {
    if [[ "$BEHIND_CDN" == "arvan" ]]; then
        local src="$TEMPLATE_DIR/nginx/arvan-ips.conf"
        [[ -f "$src" ]] || die BUNDLE_MISSING "arvan-ips.conf not found at $src"
        log "writing $REALIP_SNIPPET (Arvan real-IP restoration)"
        cp "$src" "$REALIP_SNIPPET"
        if ! grep -qE '^\s*set_real_ip_from' "$REALIP_SNIPPET"; then
            warn "$REALIP_SNIPPET has no set_real_ip_from entries — add the CDN's origin-pull CIDRs from the Arvan panel"
        fi
    elif [[ ! -f "$REALIP_SNIPPET" ]]; then
        : > "$REALIP_SNIPPET"
    fi
}

# nginx changes are transactional: the existing configuration must pass `nginx -t` before
# anything is touched (a broken one is not ours to fix), our site is backed up, and restored if the
# new one fails. Other sites are never touched.
write_nginx_config() {
    local cert_dir="$1"
    local template="$TEMPLATE_DIR/nginx/vmessenger-node.conf.template"
    [[ -f "$template" ]] || die BUNDLE_MISSING "nginx template not found at $template"
    log "writing nginx site $NGINX_SITE (certs: $cert_dir, port $PUBLIC_PORT)"
    local default_server=""
    [[ "$SERVER_NAME" == "_" ]] && default_server=" default_server"
    local filter=()
    [[ "$NO_HTTP" == true ]] && filter+=(-e '/@HTTP@/d')
    [[ -f /proc/net/if_inet6 ]] || filter+=(-e '/@IPV6@/d')
    [[ -f "$NGINX_SITE" ]] && cp "$NGINX_SITE" "$NGINX_SITE.vmessenger-backup"
    sed ${filter[@]+"${filter[@]}"} \
        -e "s|__SERVER_NAME__|${SERVER_NAME}|g" \
        -e "s|__NODE_PORT__|${NODE_PORT}|g" \
        -e "s|__CERT_DIR__|${cert_dir}|g" \
        -e "s|__PUBLIC_PORT__|${PUBLIC_PORT}|g" \
        -e "s|__DEFAULT_SERVER__|${default_server}|g" \
        "$template" > "$NGINX_SITE"
    ln -sf "$NGINX_SITE" /etc/nginx/sites-enabled/vmessenger-node.conf
}

# Fails before any change when nginx's configuration is already broken.
check_nginx_before() {
    command -v nginx >/dev/null 2>&1 || return 0
    if ! nginx -t >/dev/null 2>&1; then
        die NGINX_CONFIG_BROKEN "nginx's configuration is already invalid ($(nginx -t 2>&1 | grep -m1 emerg || true)); fix it first"
    fi
}

# EC P-256, and the key is kept across re-runs, so the node's pin survives a renewed certificate or
# a changed address. A new certificate is made when it is missing, names another host, or expires
# within 30 days.
generate_selfsigned_certificate() {
    local cert="$CERT_DIR/fullchain.pem" key="$CERT_DIR/privkey.pem" san cn
    need_cmd openssl
    install -d -m 0750 "$CERT_DIR"
    san="$(build_san_list)"
    cn="${DOMAIN:-$PUBLIC_NAME}"
    if [[ "$FORCE_CERT" == false && -f "$cert" && -f "$key" ]] \
        && openssl x509 -in "$cert" -noout -checkend 2592000 >/dev/null 2>&1 \
        && openssl x509 -in "$cert" -noout -ext subjectAltName 2>/dev/null | grep -qF "$(printf '%s' "${san%%,*}" | sed 's/^IP:/IP Address:/')"; then
        log "self-signed certificate in $CERT_DIR is current"
        return
    fi
    local key_args=(-newkey ec -pkeyopt ec_paramgen_curve:P-256 -keyout "$key")
    if [[ -f "$key" ]]; then
        key_args=(-key "$key")
        log "renewing the self-signed certificate on the same key (the pin stays)"
    else
        log "generating a self-signed certificate (EC P-256, ${CERT_VALID_DAYS} days) in $CERT_DIR"
    fi
    openssl req -x509 -nodes "${key_args[@]}" -days "$CERT_VALID_DAYS" -out "$cert" \
        -subj "/CN=$cn" -addext "subjectAltName=$san" -addext "basicConstraints=critical,CA:FALSE" 2>/dev/null \
        || die TLS_CERT_FAILED "openssl could not make a certificate"
    chmod 0640 "$key" "$cert"
    chown root:root "$key" "$cert"
}

build_san_list() {
    local sans=() host="$PUBLIC_NAME"
    [[ -n "$DOMAIN" ]] && sans+=("DNS:$DOMAIN")
    if [[ -n "$PUBLIC_IP" ]]; then host="$PUBLIC_IP"; fi
    if [[ "$host" =~ ^[0-9.]+$ || "$host" == *:* ]]; then
        sans+=("IP:$host")
    elif [[ "$host" != "$DOMAIN" ]]; then
        sans+=("DNS:$host")
    fi
    sans+=("DNS:localhost")
    local IFS=","
    printf '%s' "${sans[*]}"
}

# The key pin of a certificate file: base64url SHA-256 of its SubjectPublicKeyInfo.
pin_of() {
    openssl x509 -in "$1" -pubkey -noout | openssl pkey -pubin -outform der \
        | openssl dgst -sha256 -binary | base64 -w0 | tr '+/' '-_' | tr -d '='
}

le_cert_dir() { printf '%s/%s' "$LE_LIVE_DIR" "$DOMAIN"; }

le_cert_exists() {
    [[ -f "$(le_cert_dir)/fullchain.pem" && -f "$(le_cert_dir)/privkey.pem" ]]
}

# Does http://DOMAIN/.well-known/acme-challenge/ reach this server? Tried before certbot, which
# would otherwise spend Let's Encrypt's rate limit on a domain pointing elsewhere.
acme_probe() {
    local token probe
    token="vmessenger-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
    probe="$ACME_WEBROOT/.well-known/acme-challenge/$token"
    install -d "$(dirname "$probe")"
    printf '%s' "$token" > "$probe"
    local answer
    answer="$(curl -fsS -m 15 "http://${DOMAIN}/.well-known/acme-challenge/$token" 2>/dev/null || true)"
    rm -f "$probe"
    [[ "$answer" == "$token" ]]
}

# Does DOMAIN resolve to the address clients reach this server at?
domain_points_here() {
    local want got
    [[ -n "$PUBLIC_IP" ]] || return 0
    want="$(getent ahosts "$PUBLIC_IP" 2>/dev/null | awk '{ print $1 }' | sort -u)"
    got="$(getent ahosts "$DOMAIN" 2>/dev/null | awk '{ print $1 }' | sort -u)"
    fact domain_resolves_to "$(printf '%s' "$got" | tr '\n' ' ')"
    [[ -n "$got" ]] && grep -qxF -f <(printf '%s\n' "$want") <(printf '%s\n' "$got")
}

# HTTP-01 through the webroot. Every failure is survivable: the node keeps serving the self-signed
# certificate, and its URLs carry the pin.
obtain_letsencrypt() {
    need_cmd certbot
    if [[ "$NO_HTTP" == true ]]; then
        issue LE_SKIPPED warn "port 80 is not served here, which Let's Encrypt's HTTP-01 check needs"
        return 1
    fi
    if ! acme_probe; then
        issue ACME_UNREACHABLE warn "http://${DOMAIN}/.well-known/acme-challenge/ does not reach this server (DNS not pointed here yet, or port 80 blocked)"
        return 1
    fi
    local contact_args=(--register-unsafely-without-email) out
    [[ -n "$ACME_EMAIL" ]] && contact_args=(-m "$ACME_EMAIL")
    out="$(certbot certonly --webroot -w "$ACME_WEBROOT" -d "$DOMAIN" --agree-tos "${contact_args[@]}" \
        -n --keep-until-expiring --deploy-hook 'systemctl reload nginx' 2>&1)" && {
        log "Let's Encrypt certificate issued for $DOMAIN"
        return 0
    }
    printf '%s\n' "$out" >&2
    if grep -qi 'too many' <<<"$out"; then
        issue LE_RATE_LIMITED warn "Let's Encrypt's rate limit for $DOMAIN is reached; using a pinned certificate for now"
    else
        issue LE_FAILED warn "Let's Encrypt refused: $(grep -m1 -i 'detail\|error' <<<"$out" || true)"
    fi
    return 1
}

reload_nginx() {
    if ! nginx -t >/dev/null 2>&1; then
        local why
        why="$(nginx -t 2>&1 | grep -m1 emerg || true)"
        if [[ -f "$NGINX_SITE.vmessenger-backup" ]]; then
            mv "$NGINX_SITE.vmessenger-backup" "$NGINX_SITE"
        else
            rm -f "$NGINX_SITE" /etc/nginx/sites-enabled/vmessenger-node.conf
        fi
        die NGINX_NEW_CONFIG_FAILED "nginx refused the node's site, which was taken back out: $why"
    fi
    rm -f "$NGINX_SITE.vmessenger-backup"
    systemctl enable nginx >/dev/null 2>&1 || true
    systemctl reload nginx 2>/dev/null || systemctl restart nginx
}

# The node's certificate and URLs. With a domain: Let's Encrypt, and the URLs name the domain with
# no pin. Anything short of that falls back to the self-signed certificate and pinned URLs — on the
# domain when it points here, else on the address the server was reached at.
configure_tls_and_nginx() {
    check_nginx_before
    write_realip_snippet
    generate_selfsigned_certificate
    URL_HOST="$PUBLIC_NAME"
    write_nginx_config "$CERT_DIR"
    reload_nginx
    CERT_PEM_FILE="$CERT_DIR/fullchain.pem"
    if [[ -n "$DOMAIN" && "$TLS_MODE" == letsencrypt ]]; then
        if le_cert_exists || obtain_letsencrypt; then
            write_nginx_config "$(le_cert_dir)"
            reload_nginx
            CERT_PEM_FILE="$(le_cert_dir)/fullchain.pem"
            NODE_MODE="domain-ca"
            URL_HOST="$DOMAIN"
            PIN=""
            TLS_SUMMARY="Let's Encrypt ($(le_cert_dir)); renewed by certbot.timer"
            return
        fi
        if domain_points_here; then
            NODE_MODE="domain-pinned"
            URL_HOST="$DOMAIN"
        elif [[ -n "$PUBLIC_IP" ]]; then
            issue DOMAIN_NOT_HERE warn "$DOMAIN does not point at this server; the node's addresses use $PUBLIC_IP"
            NODE_MODE="ip-pinned"
            URL_HOST="$PUBLIC_IP"
            SERVER_NAME="_"
            write_nginx_config "$CERT_DIR"
            reload_nginx
        else
            NODE_MODE="domain-pinned"
            URL_HOST="$DOMAIN"
        fi
    else
        NODE_MODE="${DOMAIN:+domain-pinned}"
        NODE_MODE="${NODE_MODE:-ip-pinned}"
    fi
    PIN="$(pin_of "$CERT_DIR/fullchain.pem")"
    fact pin "$PIN"
    TLS_SUMMARY="self-signed ($CERT_DIR), pinned: pin-sha256=$PIN"
}

# ---- ports ------------------------------------------------------------------------------------------

# The processes listening on TCP PORT, one name per line.
port_owners() {
    ss -Hltnp "sport = :$1" 2>/dev/null | { grep -o 'users:(("[^"]*"' || true; } | sed -e 's/users:(("//' -e 's/"$//' | sort -u
}

free_port_near() {
    local port="$1"
    while [[ -n "$(port_owners "$port")" ]]; do port=$((port + 1)); done
    printf '%s' "$port"
}

# Another nginx site holds this port as its default_server: fine for a domain (SNI picks the site),
# fatal for a bare IP, which has no name to pick by.
nginx_default_elsewhere() {
    { grep -rlE "listen[^;]*\b$1\b[^;]*default_server" /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null || true; } \
        | { grep -v 'vmessenger-node.conf' || true; } | head -n 1
}

# Who has the ports the node needs, and what to do about it — before anything is installed.
check_ports() {
    local owners
    owners="$(port_owners "$PUBLIC_PORT")"
    fact port_owner "$PUBLIC_PORT ${owners:-free}"
    case "$owners" in
        "" | nginx) ;;
        apache2)
            if consent PORT_APACHE "apache2 serves port $PUBLIC_PORT; stop and disable it so the node can use the port"; then
                systemctl disable --now apache2 >/dev/null 2>&1 || die PORT_BUSY "apache2 would not stop"
            fi
            ;;
        *)
            fact free_port "$(free_port_near 8444)"
            consent PUBLIC_PORT_TAKEN "port $PUBLIC_PORT is in use by ${owners//$'\n'/, }; choose another port" || true
            ;;
    esac
    if [[ -z "$DOMAIN" || "$SERVER_NAME" == "_" ]]; then
        local other
        other="$(nginx_default_elsewhere "$PUBLIC_PORT")"
        if [[ -n "$other" ]]; then
            fact free_port "$(free_port_near 8444)"
            consent PUBLIC_PORT_TAKEN "another site ($other) is port $PUBLIC_PORT's default; a node on a bare IP needs a port of its own" || true
        fi
    fi
    if [[ "$NO_HTTP" == false ]]; then
        owners="$(port_owners 80)"
        case "$owners" in
            "" | nginx) ;;
            apache2) if allowed PORT_APACHE; then :; else NO_HTTP=true; issue HTTP_SKIPPED info "port 80 is apache2's; serving no plain HTTP"; fi ;;
            *) NO_HTTP=true; issue HTTP_SKIPPED info "port 80 is in use by $owners; serving no plain HTTP" ;;
        esac
    fi
    owners="$(port_owners "$NODE_PORT")"
    if [[ -n "$owners" && "$owners" != java ]]; then
        NODE_PORT="$(free_port_near "$NODE_PORT")"
        issue NODE_PORT_MOVED info "the node's local port is taken by $owners; using $NODE_PORT"
    fi
}

# ufw, if it is already on, lets the node's ports through. It is never turned on.
open_firewall_ports() {
    command -v ufw >/dev/null 2>&1 || return 0
    ufw status 2>/dev/null | grep -q '^Status: active' || return 0
    ufw allow "$PUBLIC_PORT/tcp" >/dev/null
    [[ "$NO_HTTP" == true ]] || ufw allow 80/tcp >/dev/null
    issue UFW_OPENED info "ufw is on: allowed $PUBLIC_PORT/tcp$([[ "$NO_HTTP" == true ]] || printf ' and 80/tcp')"
}

# What the node advertises about itself, rewritten on every run (the operator's node.env wins).
write_managed_env() {
    install -d -m 0755 "$(dirname "$MANAGED_ENV_FILE")"
    {
        printf '# Written by setup-node.sh on every run; put overrides in %s.\n' "$NODE_ENV_FILE"
        printf 'VMESSENGER_PUBLIC_HOST=%s\n' "$URL_HOST"
        printf 'VMESSENGER_ADVERTISED_DHT_URL=%s\n' "$(node_url /dht)"
    } > "$MANAGED_ENV_FILE"
    chmod 0644 "$MANAGED_ENV_FILE"
}

start_node_service() {
    systemctl daemon-reload
    systemctl enable vmessenger-node >/dev/null
    systemctl restart vmessenger-node
}

# The node answers locally, through nginx with the pin (or the CA), and upgrades /relay; and it
# advertises the URL clients will use. A node that fails any of it is rolled back.
health_check() {
    local i ok=false
    for i in $(seq 1 45); do
        if curl -fsS -m 2 "http://127.0.0.1:${NODE_PORT}/healthz" 2>/dev/null | grep -q '^ok'; then
            ok=true
            break
        fi
        sleep 1
    done
    if [[ "$ok" == false ]]; then
        journalctl -u vmessenger-node -n 30 --no-pager >&2 || true
        rollback_node && die HEALTH_LOCAL_FAILED "the new node did not start; the previous one is back"
        die HEALTH_LOCAL_FAILED "node did not become healthy on 127.0.0.1:${NODE_PORT} ($(node_start_failure))"
    fi
    log "node healthy on 127.0.0.1:${NODE_PORT}"
    local base tls=()
    base="https://$(url_authority "$URL_HOST")"
    if [[ -n "$PIN" ]]; then
        tls=(-k --pinnedpubkey "sha256//$(printf '%s' "$PIN" | tr '_-' '/+')=")
    fi
    local resolve=(--resolve "$URL_HOST:$PUBLIC_PORT:127.0.0.1")
    [[ "$URL_HOST" == *:* ]] && resolve=(--resolve "[$URL_HOST]:$PUBLIC_PORT:127.0.0.1")
    if ! curl -fsS -m 10 "${tls[@]}" "${resolve[@]}" "$base/healthz" 2>/dev/null | grep -q '^ok'; then
        die HEALTH_TLS_FAILED "https://$(url_authority "$URL_HOST")/healthz through nginx did not answer with the expected certificate"
    fi
    local code
    code="$(curl -sS -m 10 -o /dev/null -w '%{http_code}' "${tls[@]}" "${resolve[@]}" --http1.1 \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
        -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "$base/relay" 2>/dev/null || true)"
    [[ "$code" == 101 ]] || die RELAY_UPGRADE_FAILED "/relay answered $code through nginx, not 101"
    if ! curl -fsS -m 5 "http://127.0.0.1:${NODE_PORT}/healthz?verbose=1" 2>/dev/null | grep -qF "advertisedDhtUrl=$(node_url /dht)"; then
        issue ADVERTISED_URL_MISMATCH warn "the node advertises a DHT URL other than $(node_url /dht) (an override in $NODE_ENV_FILE?)"
    fi
    log "nginx → node healthy on $base"
}

# Why the node did not start, from its journal.
node_start_failure() {
    local journal
    journal="$(journalctl -u vmessenger-node -n 60 --no-pager 2>/dev/null || true)"
    if grep -q 'UnsupportedClassVersionError' <<<"$journal"; then printf 'the JRE is too old'
    elif grep -q 'Address already in use' <<<"$journal"; then printf 'port %s is taken' "$NODE_PORT"
    elif grep -q 'OutOfMemoryError' <<<"$journal"; then printf 'out of memory'
    else printf 'see journalctl -u vmessenger-node'
    fi
}

print_terminal_qr() {
    local title="$1"
    local payload="$2"
    printf '\n%s\n' "$title"
    printf '%s\n' "$payload"
    if command -v qrencode >/dev/null 2>&1; then
        qrencode -t ANSIUTF8 "$payload" 2>/dev/null || qrencode -t UTF8 "$payload"
    else
        printf '(install qrencode for terminal QR preview)\n'
    fi
}

print_success() {
    local bootstrap_link relay_link
    bootstrap_link="vmnode:bootstrap:$(node_url /dht)"
    relay_link="vmnode:relay:$(node_url /relay)"
    local install_one_liner="curl -fsSL ${SETUP_SCRIPT_URL} | sudo bash -s --"
    cat <<EOF

vMessenger node is running.

  Health:  https://$(url_authority "$URL_HOST")/healthz
  DHT:     $(node_url /dht)
  Relay:   $(node_url /relay)

TLS:      ${TLS_SUMMARY}
Service:  systemctl status vmessenger-node
Logs:     journalctl -u vmessenger-node -f
Config:   ${NODE_ENV_FILE}
EOF
    if [[ "$BEHIND_CDN" != "none" ]]; then
        cat <<EOF

CDN checklist (${BEHIND_CDN}): origin = this host :443 (HTTPS), WebSocket enabled for /dht and /relay,
cache bypass for /healthz /dht /relay, origin read timeout as high as the plan allows for /relay,
and (once Let's Encrypt is installed) strict origin certificate validation.
EOF
    fi
    [[ "$FROM_APP" == false ]] || return 0
    print_terminal_qr "Scan in app (تنظیمات → گره‌های شبکه → اسکن QR) — bootstrap:" "$bootstrap_link"
    print_terminal_qr "Scan in app — relay:" "$relay_link"
    print_terminal_qr "One-line install script (share to deploy another node):" "$install_one_liner"
    printf '\n%s\n' "$install_one_liner"
}


# ---- hardening --------------------------------------------------------------------------------------

ssh_ports() { sshd -T 2>/dev/null | awk '/^port / { print $2 }' | sort -u | paste -sd, -; }

# fail2ban on the SSH port(s), read from journald (no auth.log on a minimal Debian).
harden_fail2ban() {
    step fail2ban start
    local pkgs=(fail2ban)
    apt_has_candidate python3-systemd && pkgs+=(python3-systemd)
    apt_install "${pkgs[@]}" >/dev/null
    install -d -m 0755 "$(dirname "$F2B_JAIL")"
    cat > "$F2B_JAIL" <<EOF
# Written by setup-node.sh --secure.
[sshd]
enabled = true
backend = systemd
port = $(ssh_ports)
maxretry = 5
findtime = 10m
bantime = 1h
EOF
    systemctl enable fail2ban >/dev/null 2>&1 || true
    if systemctl restart fail2ban && sleep 2 && fail2ban-client status sshd >/dev/null 2>&1; then
        HARDENING_F2B="on"
        step fail2ban ok
    else
        issue HARDEN_F2B_FAILED warn "fail2ban did not start: $(journalctl -u fail2ban -n 3 --no-pager 2>/dev/null | tail -n 1)"
        step fail2ban warn
    fi
}

# Security updates installed daily by unattended-upgrades (its stock origins are the distribution's
# security suites); our file only switches the periodic runs on.
harden_updates() {
    step updates start
    apt_install unattended-upgrades >/dev/null
    cat > "$UNATTENDED_CONF" <<'EOF'
// Written by setup-node.sh --secure.
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
    systemctl enable --now apt-daily.timer apt-daily-upgrade.timer >/dev/null 2>&1 || true
    HARDENING_UPDATES="on"
    step updates ok
}

# Time sync, unless chrony or ntpd already keeps time. TLS, apt and relay proofs all need a clock.
harden_time() {
    step timesync start
    if systemctl is-active --quiet chrony || systemctl is-active --quiet chronyd || systemctl is-active --quiet ntp; then
        HARDENING_TIME="existing"
        step timesync skip "chrony or ntp already keeps time"
        return
    fi
    if [[ -z "$(systemctl list-unit-files systemd-timesyncd.service --no-legend 2>/dev/null)" ]]; then
        apt_install systemd-timesyncd >/dev/null
    fi
    timedatectl set-ntp true >/dev/null 2>&1 || systemctl enable --now systemd-timesyncd >/dev/null 2>&1 || true
    HARDENING_TIME="on"
    step timesync ok
}

reload_sshd() { systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true; }

ssh_rollback() {
    rm -f "$SSH_HARDENING_FILE"
    reload_sshd
    systemctl stop "$SSH_ROLLBACK_UNIT.timer" >/dev/null 2>&1 || true
}

# Password logins off, in a drop-in that sshd reads first. Armed with a rollback timer before it
# takes effect, and kept only when the app proves a fresh key-only login works (--confirm-ssh);
# otherwise undone, so a mistake cannot lock the owner out.
harden_ssh() {
    step ssh start
    local home keys
    home="$(getent passwd "$SSH_USER" | cut -d: -f6)"
    keys="$home/.ssh/authorized_keys"
    if [[ -z "$home" || ! -s "$keys" ]]; then
        issue HARDEN_SSH_NO_KEYS warn "$SSH_USER has no authorized_keys; password logins stay on"
        HARDENING_SSH="skipped"
        step ssh skip
        return
    fi
    if ! grep -Eqi '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' /etc/ssh/sshd_config; then
        issue HARDEN_SSH_NO_INCLUDE warn "sshd_config does not read sshd_config.d; password logins stay on"
        HARDENING_SSH="skipped"
        step ssh skip
        return
    fi
    {
        printf '# Written by setup-node.sh --key-only-ssh. Delete it and reload ssh to allow passwords again.\n'
        printf 'PasswordAuthentication no\n'
        printf 'KbdInteractiveAuthentication no\n'
        # Only tightened, never loosened: "prohibit-password" where root could log in with one.
        if sshd -T 2>/dev/null | grep -qi '^permitrootlogin yes'; then printf 'PermitRootLogin prohibit-password\n'; fi
    } > "$SSH_HARDENING_FILE"
    if ! sshd -t 2>/dev/null; then
        rm -f "$SSH_HARDENING_FILE"
        issue HARDEN_SSH_INVALID warn "sshd rejected the hardening drop-in; nothing changed"
        HARDENING_SSH="skipped"
        step ssh skip
        return
    fi
    if ! sshd -T -C "user=$SSH_USER,host=localhost,addr=127.0.0.1" 2>/dev/null | grep -qi '^passwordauthentication no'; then
        rm -f "$SSH_HARDENING_FILE"
        issue HARDEN_SSH_OVERRIDDEN warn "another sshd setting (a Match block?) keeps password logins on for $SSH_USER; nothing changed"
        HARDENING_SSH="skipped"
        step ssh skip
        return
    fi
    systemctl stop "$SSH_ROLLBACK_UNIT.timer" >/dev/null 2>&1 || true
    systemd-run --unit="$SSH_ROLLBACK_UNIT" --on-active=$((SSH_CONFIRM_SECONDS + 10)) --quiet \
        /bin/sh -c "rm -f '$SSH_HARDENING_FILE'; systemctl reload ssh || systemctl reload sshd || true" \
        || die HARDEN_SSH_TIMER "could not arm the rollback timer; password logins stay on"
    reload_sshd
    rm -f "$RUN_DIR/ssh-confirmed"
    step ssh wait "confirm with a fresh key-only login"
    fact ssh_confirm "$RUN_ID"
    local waited=0
    while [[ ! -f "$RUN_DIR/ssh-confirmed" && "$waited" -lt "$SSH_CONFIRM_SECONDS" ]]; do
        sleep 2
        waited=$((waited + 2))
    done
    if [[ -f "$RUN_DIR/ssh-confirmed" ]]; then
        systemctl stop "$SSH_ROLLBACK_UNIT.timer" >/dev/null 2>&1 || true
        HARDENING_SSH="applied"
        step ssh ok
    else
        ssh_rollback
        HARDENING_SSH="rolled-back"
        issue HARDEN_SSH_ROLLED_BACK warn "no key-only login confirmed within ${SSH_CONFIRM_SECONDS}s; password logins are on again"
        step ssh warn
    fi
}

harden_server() {
    if [[ "$SECURE" == true ]]; then
        harden_fail2ban
        harden_updates
        harden_time
    fi
    [[ "$KEY_ONLY_SSH" == false ]] || harden_ssh
}

cmd_confirm_ssh() {
    run_dir_or_die
    : > "$RUN_DIR/ssh-confirmed"
    mark confirmed run="$RUN_ID"
    [[ "$FROM_APP" == true ]] || printf 'confirmed %s\n' "$RUN_ID"
}

# Takes out what the installer put in: the service, its unit, its nginx site, the node's files and
# the files the installer writes. Other nginx sites, and the hardening (the server's now), stay.
# --purge also removes the node's identity (node.seed) and state, its user and the installer's runs.
cmd_uninstall() {
    mark hello proto="$PROTOCOL_VERSION" action=uninstall
    step uninstall start
    systemctl disable --now vmessenger-node >/dev/null 2>&1 || true
    rm -f "$SYSTEMD_UNIT"
    systemctl daemon-reload
    rm -f /etc/nginx/sites-enabled/vmessenger-node.conf "$NGINX_SITE" "$NGINX_SITE.vmessenger-backup" "$REALIP_SNIPPET"
    if command -v nginx >/dev/null 2>&1 && nginx -t >/dev/null 2>&1; then systemctl reload nginx 2>/dev/null || true; fi
    rm -rf "$INSTALL_DIR" "${INSTALL_DIR}.prev" "${INSTALL_DIR}.new" "$MANAGED_ENV_FILE" "$INSTALL_RECORD"
    if [[ "$PURGE" == true ]]; then
        rm -rf "$STATE_DIR" /etc/vmessenger "$INSTALLER_HOME"
        userdel "$NODE_USER" >/dev/null 2>&1 || true
    fi
    step uninstall ok
    if [[ "$PURGE" == true ]]; then log "vMessenger node removed, with its identity and state"; else log "vMessenger node removed"; fi
}

# ---- machine mode ----------------------------------------------------------------------------------

read_os_release() {
    [[ -z "$OS_ID" ]] || return 0
    OS_ID="$(os_release_field ID)"
    OS_VERSION_ID="$(os_release_field VERSION_ID)"
    OS_CODENAME="$(os_release_field VERSION_CODENAME)"
    OS_PRETTY="$(os_release_field PRETTY_NAME)"
    ARCH="$(uname -m)"
}

# Read, not sourced: os-release is data, and sourcing it runs whatever it contains as root.
os_release_field() {
    [[ -r /etc/os-release ]] || return 0
    sed -n "s/^$1=//p" /etc/os-release | head -n 1 | sed -e 's/^["'\'']//' -e 's/["'\'']$//'
}

resolve_bundle() {
    [[ -d "$BUNDLE_DIR" ]] || die BUNDLE_MISSING "no bundle at $BUNDLE_DIR"
    BUNDLE_DIR="$(cd "$BUNDLE_DIR" && pwd)"
    [[ -f "$BUNDLE_DIR/manifest.json" ]] || die BUNDLE_MISSING "no manifest.json in $BUNDLE_DIR"
    [[ -n "$(bundle_version)" ]] || die BUNDLE_CORRUPT "manifest.json names no nodeVersion"
}

# The version the bundle installs, from manifest.json (written by the app's build, not by hand).
bundle_version() {
    sed -n 's/.*"nodeVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$BUNDLE_DIR/manifest.json" | head -n 1
}

bundle_tarball() { printf '%s/vmessenger-node-%s.tar.gz' "$BUNDLE_DIR" "$(bundle_version)"; }

verify_bundle() {
    [[ -f "$BUNDLE_DIR/SHA256SUMS" ]] || die BUNDLE_CORRUPT "no SHA256SUMS in $BUNDLE_DIR"
    if ! (cd "$BUNDLE_DIR" && sha256sum --check --quiet --strict SHA256SUMS) >/dev/null 2>&1; then
        die BUNDLE_CORRUPT "bundle files do not match SHA256SUMS (a truncated upload?)"
    fi
    [[ -f "$(bundle_tarball)" ]] || die BUNDLE_MISSING "no $(basename "$(bundle_tarball)") in the bundle"
}

new_run_id() {
    printf '%s-%s' "$(date -u +%Y%m%d-%H%M%S)" "$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
}

# The id of the install that is running now, if any.
active_run() {
    local unit
    unit="$(systemctl list-units --type=service --state=activating,active --no-legend --plain \
        "${RUN_UNIT_PREFIX}*" 2>/dev/null | awk 'NR == 1 { print $1 }')"
    [[ -n "$unit" ]] || return 1
    unit="${unit%.service}"
    printf '%s' "${unit#"$RUN_UNIT_PREFIX"}"
}

cmd_version() {
    local version="none"
    if [[ -n "$BUNDLE_DIR" && -f "$BUNDLE_DIR/manifest.json" ]]; then version="$(bundle_version)"; fi
    printf 'vmessenger-installer protocol=%s bundle=%s\n' "$PROTOCOL_VERSION" "$version"
}

# Facts about this server and the problems that would stop an install, without changing anything.
# Exit 0: go ahead. 10: decisions to make (the consent issues say which). 20/30/40: see §3.4.
cmd_preflight() {
    mark hello proto="$PROTOCOL_VERSION" installer="$(bundle_version)" action=preflight
    step preflight start
    preflight_checks
    if [[ "${#PENDING_CONSENTS[@]}" -gt 0 ]]; then
        step preflight wait "decisions needed: ${PENDING_CONSENTS[*]}"
        FATAL_CODE="${PENDING_CONSENTS[0]}"
        exit 10
    fi
    step preflight ok
}

# Starts the install as a transient systemd service and returns at once. The run outlives this SSH
# session, a dropped connection and the app; --follow picks its log up again from any byte.
cmd_launch() {
    need_cmd systemd-run
    verify_bundle
    install -d -m 0700 "$INSTALLER_HOME" "$RUNS_DIR" "$BUNDLES_DIR"
    local active
    if active="$(active_run)"; then
        fact active_run "$active"
        die INSTALL_BUSY "install $active is still running"
    fi
    local version dest id
    version="$(bundle_version)"
    dest="$BUNDLES_DIR/$version"
    # Root-owned copy: the run executes it after this session is gone, and it is what a later
    # update rolls back to.
    if [[ "$BUNDLE_DIR" != "$dest" ]]; then
        rm -rf "$dest.new"
        cp -R "$BUNDLE_DIR/." "$dest.new"
        chown -R root:root "$dest.new"
        chmod -R go-w "$dest.new"
        rm -rf "$dest"
        mv "$dest.new" "$dest"
    fi
    id="$(new_run_id)"
    RUN_DIR="$RUNS_DIR/$id"
    install -d -m 0700 "$RUN_DIR"
    : > "$RUN_DIR/log"
    printf '%q ' "${INSTALL_ARGS[@]}" > "$RUN_DIR/args"
    if ! systemd-run --unit="${RUN_UNIT_PREFIX}${id}" --description="vMessenger node install $id" \
        --collect --quiet --setenv=HOME=/root --setenv=LC_ALL=C \
        --property="StandardOutput=append:$RUN_DIR/log" --property="StandardError=append:$RUN_DIR/log" \
        /bin/bash "$dest/setup-node.sh" --from-app --run "$id" --bundle-dir "$dest" "${INSTALL_ARGS[@]}"; then
        die RUN_START_FAILED "systemd-run could not start the install"
    fi
    mark launched run="$id"
    log "install $id started; follow it with: $0 --from-app --follow $id"
    prune_runs "$id"
}

# Entries of a directory, newest first. Run ids begin with a UTC timestamp, so name order is age.
newest_first() {
    local entry
    for entry in "$1"/*; do
        [[ -e "$entry" ]] && printf '%s\n' "${entry##*/}"
    done | sort -r
}

# Keeps the newest RUNS_KEPT runs and the two newest bundles.
prune_runs() {
    local keep="$1" dir
    newest_first "$RUNS_DIR" | tail -n +"$((RUNS_KEPT + 1))" | while read -r dir; do
        [[ "$dir" == "$keep" ]] || rm -rf "${RUNS_DIR:?}/$dir"
    done
    find "$BUNDLES_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' 2>/dev/null | sort -rn | tail -n +3 \
        | while read -r _ dir; do rm -rf "${BUNDLES_DIR:?}/$dir"; done
}

# The detached run itself (started by --launch through systemd-run).
cmd_run() {
    RUN_DIR="$RUNS_DIR/$RUN_ID"
    [[ -d "$RUN_DIR" ]] || die RUN_UNKNOWN "no run $RUN_ID"
    SEQ_FILE="$RUN_DIR/seq"
    : > "$SEQ_FILE"
    exec 9>"$LOCK_FILE"
    flock -n 9 || die INSTALL_BUSY "another install holds $LOCK_FILE"
    mark hello proto="$PROTOCOL_VERSION" installer="$(bundle_version)" run="$RUN_ID" action=run
    setup_production
}

# Runs from the EXIT trap. The end marker is always the log's last line, and `exit` exists before
# the process does, so --follow can report how a run ended even after a crash in the middle.
finish_run() {
    local status="$1"
    [[ -n "$RUN_DIR" && -d "$RUN_DIR" ]] || return 0
    if [[ ! -f "$RUN_DIR/result.json" ]]; then
        write_result failed "$status" || true
    fi
    printf '%s\n' "$status" > "$RUN_DIR/exit"
    mark end status="$status"
}

run_dir_or_die() {
    RUN_DIR="$RUNS_DIR/$RUN_ID"
    [[ -d "$RUN_DIR" ]] || die RUN_UNKNOWN "no run $RUN_ID on this server"
}

# Streams the run's log from --from-byte on, and keeps streaming until the run ends. The output is
# the log itself, markers and all; the exit status is the run's.
cmd_follow() {
    run_dir_or_die
    local pid
    pid="$(systemctl show -p MainPID --value "${RUN_UNIT_PREFIX}${RUN_ID}.service" 2>/dev/null || true)"
    if [[ -n "$pid" && "$pid" != 0 ]]; then
        tail -c +"$((FROM_BYTE + 1))" --pid="$pid" -f "$RUN_DIR/log"
    else
        tail -c +"$((FROM_BYTE + 1))" "$RUN_DIR/log"
    fi
    local status
    status="$(cat "$RUN_DIR/exit" 2>/dev/null || true)"
    exit "${status:-1}"
}

cmd_status() {
    run_dir_or_die
    local state="running" status="" bytes
    status="$(cat "$RUN_DIR/exit" 2>/dev/null || true)"
    if [[ -n "$status" ]]; then
        state="failed"
        [[ "$status" == 0 ]] && state="done"
    elif ! systemctl is-active --quiet "${RUN_UNIT_PREFIX}${RUN_ID}.service"; then
        state="lost"
    fi
    bytes="$(wc -c < "$RUN_DIR/log" | tr -d ' ')"
    mark status run="$RUN_ID" state="$state" exit="${status:--}" bytes="$bytes"
    [[ "$FROM_APP" == true ]] || printf '%s %s exit=%s bytes=%s\n' "$RUN_ID" "$state" "${status:--}" "$bytes"
}

cmd_result() {
    run_dir_or_die
    [[ -f "$RUN_DIR/result.json" ]] || die RUN_UNKNOWN "run $RUN_ID has no result yet"
    cat "$RUN_DIR/result.json"
}

cmd_list_runs() {
    local dir status
    while read -r dir; do
        status="$(cat "$RUNS_DIR/$dir/exit" 2>/dev/null || printf 'running')"
        mark run run="$dir" exit="$status"
        [[ "$FROM_APP" == true ]] || printf '%s %s\n' "$dir" "$status"
    done < <(newest_first "$RUNS_DIR")
}

read_node_id() {
    NODE_ID="$(curl -fsS -m 5 "http://127.0.0.1:${NODE_PORT}/healthz?verbose=1" 2>/dev/null \
        | sed -n 's/.*"nodeId":"\([0-9a-f]*\)".*/\1/p' || true)"
    fact node_id "$NODE_ID"
}

# JSON string, or null when empty.
json_str() {
    local s="$1"
    if [[ -z "$s" ]]; then
        printf 'null'
        return
    fi
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\n'/\\n}"
    s="${s//$'\r'/\\r}"
    s="${s//$'\t'/\\t}"
    printf '"%s"' "$(printf '%s' "$s" | tr -d '\000-\010\013\014\016-\037')"
}

json_array() {
    local first=true item
    printf '['
    for item in "$@"; do
        [[ -n "$item" ]] || continue
        [[ "$first" == true ]] || printf ', '
        first=false
        json_str "$item"
    done
    printf ']'
}

# What the app reads when the run ends: schema 1. Written for failed runs too, so a client that
# lost the log can still learn why.
write_result() {
    local status="$1" exit_status="$2" file="$RUN_DIR/result.json" java_version mode
    java_version="$("${JAVA_BIN:-java}" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p' || true)"
    mode="$NODE_MODE"
    local cert_pem="" replaces=() url
    [[ -n "$CERT_PEM_FILE" && -f "$CERT_PEM_FILE" ]] && cert_pem="$(cat "$CERT_PEM_FILE")"
    for url in $PREVIOUS_URLS; do
        [[ "$url" == "$(node_url /relay)" || "$url" == "$(node_url /dht)" ]] || replaces+=("$url")
    done
    {
        printf '{\n'
        printf '  "schema": 1,\n'
        printf '  "status": %s,\n' "$(json_str "$status")"
        printf '  "exitStatus": %s,\n' "$exit_status"
        printf '  "code": %s,\n' "$(json_str "$FATAL_CODE")"
        printf '  "runId": %s,\n' "$(json_str "$RUN_ID")"
        printf '  "nodeVersion": %s,\n' "$(json_str "$(bundle_version)")"
        printf '  "nodeId": %s,\n' "$(json_str "$NODE_ID")"
        printf '  "mode": %s,\n' "$(json_str "$mode")"
        printf '  "tls": %s,\n' "$(json_str "$TLS_MODE")"
        printf '  "publicHost": %s,\n' "$(json_str "$URL_HOST")"
        printf '  "publicPort": %s,\n' "$PUBLIC_PORT"
        printf '  "domain": %s,\n' "$(json_str "$DOMAIN")"
        printf '  "bootstrapUrl": %s,\n' "$(json_str "${URL_HOST:+$(node_url /dht)}")"
        printf '  "relayUrl": %s,\n' "$(json_str "${URL_HOST:+$(node_url /relay)}")"
        printf '  "healthUrl": %s,\n' "$(json_str "${URL_HOST:+https://$(url_authority "$URL_HOST")/healthz}")"
        printf '  "pin": %s,\n' "$(json_str "$PIN")"
        printf '  "certPem": %s,\n' "$(json_str "$cert_pem")"
        printf '  "replacesUrls": %s,\n' "$(json_array "${replaces[@]:-}")"
        printf '  "os": {"id": %s, "version": %s, "arch": %s},\n' \
            "$(json_str "$OS_ID")" "$(json_str "$OS_VERSION_ID")" "$(json_str "$ARCH")"
        printf '  "java": %s,\n' "$(json_str "$java_version")"
        printf '  "aptSources": %s,\n' "$(json_str "$APT_SOURCES_NOTE")"
        printf '  "hardening": {"fail2ban": %s, "autoUpdates": %s, "timeSync": %s, "keyOnlySsh": %s},\n' \
            "$(json_str "$HARDENING_F2B")" "$(json_str "$HARDENING_UPDATES")" "$(json_str "$HARDENING_TIME")" \
            "$(json_str "$HARDENING_SSH")"
        printf '  "warnings": %s\n' "$(json_array "${WARNINGS[@]:-}")"
        printf '}\n'
    } > "$file.tmp"
    mv "$file.tmp" "$file"
    if [[ "$status" == ok ]]; then
        install -d -m 0755 "$(dirname "$INSTALL_RECORD")"
        cp "$file" "$INSTALL_RECORD"
        chmod 0644 "$INSTALL_RECORD"
    fi
    mark result status="$status" file="$file"
}

run_dev_node() {
    find_repo_root
    clone_repo_if_needed
    build_node
    cd "$REPO_ROOT"
    log "starting TCP DHT dev node on port $DEFAULT_TCP_PORT"
    log "next: ./scripts/emulator-connect.sh  (from repo root)"
    exec ./gradlew :node:run --no-daemon --args="--tcp"
}

setup_production() {
    find_repo_root
    fetch_templates
    step preflight start
    preflight_checks
    step preflight ok
    step apt start
    make_disk_room
    prepare_apt
    step apt ok
    ensure_swap
    step java start
    install_java
    step java ok
    step packages start
    install_os_packages
    step packages ok
    if [[ "$FIREWALL" == true ]]; then
        step firewall start
        configure_firewall
        step firewall ok
    fi
    step ports start
    check_ports
    open_firewall_ports
    step ports ok
    step files start
    acquire_dist
    create_system_user
    create_dirs
    install_node_files
    remove_legacy_artifacts
    step files ok
    step tls start
    configure_tls_and_nginx
    step tls ok
    step config start
    write_node_env
    write_managed_env
    write_systemd_unit
    step config ok
    step service start
    start_node_service
    step service ok
    step health start
    health_check
    read_node_id
    step health ok
    harden_server
    step finish start
    if [[ "$ACTION" == run ]]; then write_result ok 0; fi
    print_success
    step finish ok
}

main() {
    exec 3>&1
    parse_args "$@"
    require_root_for_prod
    case "$ACTION" in
        version) cmd_version ;;
        follow) cmd_follow ;;
        status) cmd_status ;;
        result) cmd_result ;;
        confirm-ssh) cmd_confirm_ssh ;;
        uninstall) cmd_uninstall ;;
        list-runs) cmd_list_runs ;;
        *)
            [[ -z "$BUNDLE_DIR" ]] || resolve_bundle
            validate_args
            case "$ACTION" in
                preflight) cmd_preflight ;;
                launch) cmd_launch ;;
                run) cmd_run ;;
                *) if [[ "$MODE" == "dev" ]]; then run_dev_node; else setup_production; fi ;;
            esac
            ;;
    esac
}

# Sourcing the script (tests) defines the functions without running anything; open fd 3 first
# (exec 3>&1), which mark writes to. Piped into bash (curl | bash) BASH_SOURCE is empty, which
# counts as being run.
if [[ "${BASH_SOURCE[0]:-$0}" == "$0" ]]; then
    main "$@"
fi
