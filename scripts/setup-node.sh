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
CURRENT_STEP=""
FATAL_CODE=""
WARNINGS=()
OS_ID=""
OS_VERSION_ID=""
OS_CODENAME=""
OS_PRETTY=""
ARCH=""
NODE_ID=""

usage() {
    cat <<'EOF'
Usage: setup-node.sh [options]

Production (requires root):
  --domain HOST         Public hostname (TLS SAN + advertised URLs). Enables Let's Encrypt by default.
  --ip ADDRESS          Public IP when no domain (default: auto-detect outbound IP)
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
# it has already seen.
mark() {
    [[ "$FROM_APP" == true ]] || return 0
    local ev="$1" kv line
    shift
    MARK_SEQ=$((MARK_SEQ + 1))
    line="##vm v=$PROTOCOL_VERSION seq=$MARK_SEQ ts=$(now_ms) ev=$ev"
    for kv in "$@"; do
        line+=" ${kv%%=*}=$(pct "${kv#*=}")"
    done
    printf '%s\n' "$line"
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
        *) printf '1' ;;
    esac
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
        preflight|launch|status|list-runs) mark end status="$status" ;;
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
            --run|--follow|--status|--result)
                need_value "$1" $#
                ACTION="${1#--}"
                RUN_ID="$2"
                shift 2
                continue
                ;;
            --from-byte) need_value "$1" $#; FROM_BYTE="$2"; shift 2; continue ;;
            --list-runs) ACTION=list-runs; shift; continue ;;
            --version) ACTION=version; shift; continue ;;
            -h|--help) usage; exit 0 ;;
        esac
        local taken=2
        case "$1" in
            --domain|--ip|--tls|--acme-email|--behind-cdn|--dist-tar|--dist-url|--install-dir|--cert-dir|--node-port)
                need_value "$1" $#
                ;;
        esac
        case "$1" in
            --domain) DOMAIN="$2" ;;
            --ip) PUBLIC_IP="$2" ;;
            --tls) TLS_MODE="$2" ;;
            --acme-email) ACME_EMAIL="$2" ;;
            --behind-cdn) BEHIND_CDN="$2" ;;
            --dist-tar) DIST_TAR="$2" ;;
            --dist-url) DIST_URL="$2" ;;
            --install-dir) INSTALL_DIR="$2" ;;
            --cert-dir) CERT_DIR="$2" ;;
            --node-port) NODE_PORT="$2" ;;
            --acme-no-email) ACME_NO_EMAIL=true; taken=1 ;;
            --firewall) FIREWALL=true; taken=1 ;;
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

install_os_packages() {
    need_cmd apt-get
    local pkgs=(openjdk-21-jre-headless nginx ca-certificates rsync qrencode curl)
    [[ "$TLS_MODE" == "selfsigned" || "$TLS_MODE" == "letsencrypt" ]] && pkgs+=(openssl)
    [[ "$TLS_MODE" == "letsencrypt" ]] && pkgs+=(certbot)
    [[ "$FIREWALL" == true ]] && pkgs+=(ufw)
    [[ "$BUILD_FROM_REPO" == true ]] && pkgs+=(openjdk-21-jdk-headless git)
    log "installing OS packages: ${pkgs[*]}"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq "${pkgs[@]}"
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

install_node_files() {
    [[ -x "$DIST_SOURCE_DIR/bin/node" ]] || die BUNDLE_MISSING "node distribution not found at $DIST_SOURCE_DIR"
    log "installing node to $INSTALL_DIR"
    rsync -a --delete "$DIST_SOURCE_DIR/" "$INSTALL_DIR/"
    chown -R "$NODE_USER:$NODE_USER" "$INSTALL_DIR"
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

write_nginx_config() {
    local cert_dir="$1"
    local template="$TEMPLATE_DIR/nginx/vmessenger-node.conf.template"
    [[ -f "$template" ]] || die BUNDLE_MISSING "nginx template not found at $template"
    log "writing nginx site $NGINX_SITE (certs: $cert_dir)"
    sed -e "s|__SERVER_NAME__|${SERVER_NAME}|g" \
        -e "s|__NODE_PORT__|${NODE_PORT}|g" \
        -e "s|__CERT_DIR__|${cert_dir}|g" \
        "$template" > "$NGINX_SITE"
    ln -sf "$NGINX_SITE" /etc/nginx/sites-enabled/vmessenger-node.conf
}

build_san_list() {
    local sans=()
    [[ -n "$DOMAIN" ]] && sans+=("DNS:$DOMAIN")
    if [[ -n "$PUBLIC_IP" ]]; then
        sans+=("IP:$PUBLIC_IP")
    elif [[ "$PUBLIC_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        sans+=("IP:$PUBLIC_NAME")
    fi
    sans+=("DNS:localhost")
    local IFS=","
    printf '%s' "${sans[*]}"
}

generate_selfsigned_certificate() {
    local cert="$CERT_DIR/fullchain.pem"
    local key="$CERT_DIR/privkey.pem"
    if [[ "$FORCE_CERT" == false && -f "$cert" && -f "$key" ]]; then
        log "self-signed certificate already exists in $CERT_DIR"
        return
    fi
    need_cmd openssl
    log "generating self-signed TLS certificate (valid ${CERT_VALID_DAYS} days) in $CERT_DIR"
    install -d -m 0750 "$CERT_DIR"
    local san_list cn
    san_list="$(build_san_list)"
    cn="${DOMAIN:-$PUBLIC_NAME}"
    openssl req -x509 -nodes -newkey rsa:2048 -days "$CERT_VALID_DAYS" \
        -keyout "$key" -out "$cert" \
        -config <(cat <<EOF
[req]
distinguished_name = req_dn
x509_extensions = req_ext
prompt = no

[req_dn]
CN = $cn

[req_ext]
subjectAltName = $san_list
basicConstraints = CA:FALSE
EOF
) -extensions req_ext
    chmod 0640 "$key" "$cert"
    chown root:root "$key" "$cert"
}

le_cert_dir() { printf '%s/%s' "$LE_LIVE_DIR" "$DOMAIN"; }

le_cert_exists() {
    [[ -f "$(le_cert_dir)/fullchain.pem" && -f "$(le_cert_dir)/privkey.pem" ]]
}

reload_nginx() {
    nginx -t
    systemctl enable nginx >/dev/null 2>&1 || true
    systemctl restart nginx
}

# HTTP-01 through the webroot. Works behind a CDN as long as the CDN forwards
# /.well-known/acme-challenge/ to this origin (probe first). On failure the
# node keeps running on the self-signed bootstrap cert.
obtain_letsencrypt() {
    need_cmd certbot
    local probe="$ACME_WEBROOT/.well-known/acme-challenge/vmessenger-probe"
    install -d "$(dirname "$probe")"
    echo probe > "$probe"
    if curl -fsS -m 15 "http://${DOMAIN}/.well-known/acme-challenge/vmessenger-probe" 2>/dev/null | grep -q probe; then
        log "ACME path reachable through http://${DOMAIN}"
    else
        warn "http://${DOMAIN}/.well-known/acme-challenge/ did not reach this origin (DNS/CDN not switched yet?). Trying certbot anyway."
    fi
    rm -f "$probe"

    local contact_args=()
    if [[ -n "$ACME_EMAIL" ]]; then
        contact_args=(-m "$ACME_EMAIL")
    else
        contact_args=(--register-unsafely-without-email)
    fi
    if certbot certonly --webroot -w "$ACME_WEBROOT" -d "$DOMAIN" \
        --agree-tos "${contact_args[@]}" -n --keep-until-expiring \
        --deploy-hook 'systemctl reload nginx'; then
        log "Let's Encrypt certificate issued for $DOMAIN"
        return 0
    fi
    local contact_flag="--acme-no-email"
    [[ -n "$ACME_EMAIL" ]] && contact_flag="--acme-email $ACME_EMAIL"
    warn "certbot failed; staying on the self-signed certificate in $CERT_DIR."
    cat >&2 <<EOF
    Fallbacks:
      * Point DNS/CDN at this host, then re-run:  sudo $0 --domain $DOMAIN $contact_flag $( [[ -n "$DIST_TAR" ]] && printf -- '--dist-tar %q' "$DIST_TAR" )
      * DNS-01 (manual TXT record):  certbot certonly --manual --preferred-challenges dns -d $DOMAIN
        then: sudo $0 --domain $DOMAIN --tls letsencrypt --skip-cert ...
EOF
    return 1
}

configure_tls_and_nginx() {
    case "$TLS_MODE" in
        selfsigned)
            if [[ "$SKIP_CERT" == true ]]; then
                [[ -f "$CERT_DIR/fullchain.pem" && -f "$CERT_DIR/privkey.pem" ]] \
                    || die TLS_CERT_MISSING "missing certificate in $CERT_DIR (need fullchain.pem and privkey.pem)"
            else
                generate_selfsigned_certificate
            fi
            write_nginx_config "$CERT_DIR"
            reload_nginx
            TLS_SUMMARY="self-signed (${CERT_DIR}); CDN must accept an untrusted origin cert, or clients must trust it"
            ;;
        letsencrypt)
            if [[ "$SKIP_CERT" == true ]]; then
                le_cert_exists || die TLS_CERT_MISSING "missing Let's Encrypt certificate in $(le_cert_dir)"
                write_nginx_config "$(le_cert_dir)"
                reload_nginx
                TLS_SUMMARY="Let's Encrypt ($(le_cert_dir))"
                return
            fi
            if le_cert_exists && [[ "$FORCE_CERT" == false ]]; then
                log "Let's Encrypt certificate already present in $(le_cert_dir)"
                write_nginx_config "$(le_cert_dir)"
                reload_nginx
                TLS_SUMMARY="Let's Encrypt ($(le_cert_dir))"
                return
            fi
            # Bootstrap: serve :443 with a self-signed cert so the site (and the
            # ACME location on :80) is up before issuance.
            generate_selfsigned_certificate
            write_nginx_config "$CERT_DIR"
            reload_nginx
            if obtain_letsencrypt; then
                write_nginx_config "$(le_cert_dir)"
                reload_nginx
                TLS_SUMMARY="Let's Encrypt ($(le_cert_dir)); auto-renew via certbot.timer"
            else
                TLS_SUMMARY="self-signed bootstrap (${CERT_DIR}) — Let's Encrypt issuance failed, see warnings above"
            fi
            ;;
    esac
}

start_node_service() {
    systemctl daemon-reload
    systemctl enable vmessenger-node >/dev/null
    systemctl restart vmessenger-node
}

health_check() {
    local i
    for i in $(seq 1 30); do
        if curl -fsS -m 2 "http://127.0.0.1:${NODE_PORT}/healthz" 2>/dev/null | grep -q '^ok'; then
            log "node healthy on 127.0.0.1:${NODE_PORT}"
            break
        fi
        sleep 1
        if [[ "$i" -eq 30 ]]; then
            journalctl -u vmessenger-node -n 30 --no-pager >&2 || true
            die HEALTH_LOCAL_FAILED "node did not become healthy on 127.0.0.1:${NODE_PORT}"
        fi
    done
    if curl -fsSk -m 5 --resolve "${DOMAIN:-localhost}:443:127.0.0.1" "https://${DOMAIN:-localhost}/healthz" 2>/dev/null | grep -q '^ok'; then
        log "nginx → node path healthy on :443"
    else
        warn "https://127.0.0.1/healthz did not answer 'ok' — check: nginx -t; journalctl -u nginx"
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
    local bootstrap_link="vmnode:bootstrap:wss://${PUBLIC_NAME}/dht"
    local relay_link="vmnode:relay:wss://${PUBLIC_NAME}/relay"
    local install_one_liner="curl -fsSL ${SETUP_SCRIPT_URL} | sudo bash -s --"
    cat <<EOF

vMessenger node is running.

  Health:  https://${PUBLIC_NAME}/healthz
  DHT:     wss://${PUBLIC_NAME}/dht
  Relay:   wss://${PUBLIC_NAME}/relay

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
cmd_preflight() {
    mark hello proto="$PROTOCOL_VERSION" installer="$(bundle_version)" action=preflight
    step preflight start
    read_os_release
    fact os_id "$OS_ID"
    fact os_version "$OS_VERSION_ID"
    fact os_codename "$OS_CODENAME"
    fact os_pretty "$OS_PRETTY"
    fact arch "$ARCH"
    fact server_time_ms "$(now_ms)"
    if [[ -f "$INSTALL_RECORD" ]]; then
        fact installed_version "$(sed -n 's/.*"nodeVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$INSTALL_RECORD" | head -n 1)"
    fi
    local active
    if active="$(active_run)"; then
        fact active_run "$active"
        die INSTALL_BUSY "install $active is still running"
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
    java_version="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p' || true)"
    mode="ip"
    [[ -n "$DOMAIN" ]] && mode="domain"
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
        printf '  "publicHost": %s,\n' "$(json_str "$PUBLIC_NAME")"
        printf '  "domain": %s,\n' "$(json_str "$DOMAIN")"
        printf '  "bootstrapUrl": %s,\n' "$(json_str "${PUBLIC_NAME:+wss://$PUBLIC_NAME/dht}")"
        printf '  "relayUrl": %s,\n' "$(json_str "${PUBLIC_NAME:+wss://$PUBLIC_NAME/relay}")"
        printf '  "healthUrl": %s,\n' "$(json_str "${PUBLIC_NAME:+https://$PUBLIC_NAME/healthz}")"
        printf '  "os": {"id": %s, "version": %s, "arch": %s},\n' \
            "$(json_str "$OS_ID")" "$(json_str "$OS_VERSION_ID")" "$(json_str "$ARCH")"
        printf '  "java": %s,\n' "$(json_str "$java_version")"
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
    read_os_release
    step packages start
    install_os_packages
    step packages ok
    if [[ "$FIREWALL" == true ]]; then
        step firewall start
        configure_firewall
        step firewall ok
    fi
    step files start
    acquire_dist
    create_system_user
    create_dirs
    install_node_files
    remove_legacy_artifacts
    step files ok
    step config start
    write_node_env
    write_systemd_unit
    write_realip_snippet
    step config ok
    step service start
    start_node_service
    step service ok
    step tls start
    configure_tls_and_nginx
    step tls ok
    step health start
    health_check
    read_node_id
    step health ok
    step finish start
    if [[ "$ACTION" == run ]]; then write_result ok 0; fi
    print_success
    step finish ok
}

main() {
    parse_args "$@"
    require_root_for_prod
    case "$ACTION" in
        version) cmd_version ;;
        follow) cmd_follow ;;
        status) cmd_status ;;
        result) cmd_result ;;
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

# Sourcing the script (tests) defines the functions without running anything. Piped into bash
# (curl | bash) BASH_SOURCE is empty, which counts as being run.
if [[ "${BASH_SOURCE[0]:-$0}" == "$0" ]]; then
    main "$@"
fi
