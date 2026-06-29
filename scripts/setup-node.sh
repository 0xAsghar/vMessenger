#!/usr/bin/env bash
#
# vMessenger node setup — production (nginx + systemd + openssl TLS) or local dev (TCP DHT).
#
# From the repo:
#   sudo ./scripts/setup-node.sh              # auto-detect public IP, openssl cert
#   sudo ./scripts/setup-node.sh --domain relay.example.com
#   ./scripts/setup-node.sh --dev
#
# One-line install on Ubuntu/Debian (no domain required):
#   curl -fsSL https://raw.githubusercontent.com/0xAsghar/vMessenger/main/scripts/setup-node.sh | sudo bash -s --
#
set -euo pipefail

readonly DEFAULT_GIT_URL="https://github.com/0xAsghar/vMessenger.git"
readonly DEFAULT_INSTALL_DIR="/opt/vmessenger"
readonly DEFAULT_NODE_USER="vmessenger"
readonly DEFAULT_NODE_PORT="8443"
readonly DEFAULT_TCP_PORT="46555"
readonly DEFAULT_CERT_DIR="/etc/vmessenger/tls"
readonly CERT_VALID_DAYS="825"
readonly SETUP_SCRIPT_URL="https://raw.githubusercontent.com/0xAsghar/vMessenger/main/scripts/setup-node.sh"

MODE="prod"
DOMAIN=""
PUBLIC_IP=""
INSTALL_DIR="$DEFAULT_INSTALL_DIR"
NODE_USER="$DEFAULT_NODE_USER"
NODE_PORT="$DEFAULT_NODE_PORT"
CERT_DIR="$DEFAULT_CERT_DIR"
GIT_URL="${VMESSENGER_GIT_URL:-$DEFAULT_GIT_URL}"
SKIP_BUILD=false
SKIP_CERT=false
FORCE_CERT=false
PUBLIC_NAME=""
SERVER_NAME=""
REPO_ROOT=""

usage() {
    cat <<'EOF'
Usage: setup-node.sh [options]

Production (requires root):
  --domain HOST       Optional public hostname (included in TLS SAN + advertised URLs)
  --ip ADDRESS        Public IP when no domain (default: auto-detect outbound IP)
  --install-dir PATH  Install path (default: /opt/vmessenger)
  --cert-dir PATH     TLS certificate directory (default: /etc/vmessenger/tls)
  --node-port PORT    JVM listen port behind nginx (default: 8443)
  --skip-cert         Skip openssl cert generation (certs must exist in --cert-dir)
  --force-cert        Regenerate TLS certificate even if one exists
  --skip-build        Skip Gradle build (use existing node/build/install/node)

Local development:
  --dev               Run TCP DHT node on :46555 (no nginx/systemd)

Environment:
  VMESSENGER_GIT_URL  Git clone URL when script is not run from a repo
  VMESSENGER_REPO     Path to an existing vMessenger clone (skip clone)

Examples:
  sudo ./scripts/setup-node.sh
  sudo ./scripts/setup-node.sh --ip 203.0.113.10
  sudo ./scripts/setup-node.sh --domain relay.example.com
  ./scripts/setup-node.sh --dev
EOF
}

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --domain) DOMAIN="${2:-}"; shift 2 ;;
            --ip) PUBLIC_IP="${2:-}"; shift 2 ;;
            --install-dir) INSTALL_DIR="${2:-}"; shift 2 ;;
            --cert-dir) CERT_DIR="${2:-}"; shift 2 ;;
            --node-port) NODE_PORT="${2:-}"; shift 2 ;;
            --skip-cert) SKIP_CERT=true; shift ;;
            --force-cert) FORCE_CERT=true; shift ;;
            --skip-build) SKIP_BUILD=true; shift ;;
            --dev) MODE="dev"; shift ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown argument: $1 (try --help)" ;;
        esac
    done
}

require_root_for_prod() {
    if [[ "$MODE" == "prod" && "$(id -u)" -ne 0 ]]; then
        die "production setup must run as root (use sudo)"
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
        PUBLIC_IP="$(detect_public_ip)" || die "could not detect public IP — pass --ip or --domain"
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
}

find_repo_root() {
    if [[ -n "${VMESSENGER_REPO:-}" ]]; then
        REPO_ROOT="$(cd "$VMESSENGER_REPO" && pwd)"
        return
    fi
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if [[ -f "$script_dir/../gradlew" ]]; then
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
    tmp="$(mktemp -d /tmp/vmessenger-setup.XXXXXX)"
    log "cloning $GIT_URL (shallow) into $tmp"
    git clone --depth 1 "$GIT_URL" "$tmp"
    REPO_ROOT="$tmp"
}

install_os_packages() {
    need_cmd apt-get
    log "installing OS packages (openjdk-21, nginx, openssl, git)..."
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq openjdk-21-jdk-headless nginx openssl git ca-certificates rsync qrencode
}

build_node() {
    if [[ "$SKIP_BUILD" == true ]]; then
        log "skipping build (--skip-build)"
        return
    fi
    [[ -n "$REPO_ROOT" ]] || die "no repo root for build"
    log "building node (:node:installDist)..."
    cd "$REPO_ROOT"
    chmod +x ./gradlew
    ./gradlew :node:installDist -q --no-daemon
}

node_dist_dir() {
    local dist="$REPO_ROOT/node/build/install/node"
    [[ -d "$dist/bin" ]] || die "node install dist not found at $dist — run build first"
    printf '%s' "$dist"
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

install_node_files() {
    local dist
    dist="$(node_dist_dir)"
    log "installing node to $INSTALL_DIR"
    install -d -o "$NODE_USER" -g "$NODE_USER" "$INSTALL_DIR"
    rsync -a --delete "$dist/" "$INSTALL_DIR/"
    chown -R "$NODE_USER:$NODE_USER" "$INSTALL_DIR"
}

create_system_user() {
    if ! id "$NODE_USER" &>/dev/null; then
        log "creating system user $NODE_USER"
        useradd --system --home "$INSTALL_DIR" --shell /usr/sbin/nologin "$NODE_USER"
    fi
}

write_systemd_unit() {
    log "writing systemd unit vmessenger-node.service"
    cat > /etc/systemd/system/vmessenger-node.service <<EOF
[Unit]
Description=vMessenger DHT + Relay Node
After=network.target

[Service]
Type=simple
User=$NODE_USER
WorkingDirectory=$INSTALL_DIR
Environment=VMESSENGER_NODE_PORT=$NODE_PORT
Environment=VMESSENGER_PUBLIC_HOST=$PUBLIC_NAME
ExecStart=$INSTALL_DIR/bin/node
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
}

write_nginx_config() {
    local template="$REPO_ROOT/deploy/nginx/vmessenger-node.conf.template"
    local out="/etc/nginx/sites-available/vmessenger-node.conf"
    [[ -f "$template" ]] || die "nginx template not found at $template"
    log "writing nginx site $out"
    sed -e "s/__SERVER_NAME__/$SERVER_NAME/g" \
        -e "s/__NODE_PORT__/$NODE_PORT/g" \
        -e "s/__CERT_DIR__/$CERT_DIR/g" \
        "$template" > "$out"
    ln -sf "$out" /etc/nginx/sites-enabled/vmessenger-node.conf
    rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true
}

build_san_list() {
    local sans=()
    if [[ -n "$DOMAIN" ]]; then
        sans+=("DNS:$DOMAIN")
    fi
    if [[ -n "$PUBLIC_IP" ]]; then
        sans+=("IP:$PUBLIC_IP")
    elif [[ "$PUBLIC_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        sans+=("IP:$PUBLIC_NAME")
    fi
    sans+=("DNS:localhost")
    local joined=""
    local entry
    for entry in "${sans[@]}"; do
        if [[ -n "$joined" ]]; then
            joined+=",$entry"
        else
            joined="$entry"
        fi
    done
    printf '%s' "$joined"
}

generate_tls_certificate() {
    local cert="$CERT_DIR/fullchain.pem"
    local key="$CERT_DIR/privkey.pem"

    if [[ "$SKIP_CERT" == true ]]; then
        log "skipping certificate generation (--skip-cert)"
        [[ -f "$cert" && -f "$key" ]] || die "missing certificate in $CERT_DIR (need fullchain.pem and privkey.pem)"
        return
    fi

    if [[ "$FORCE_CERT" == false && -f "$cert" && -f "$key" ]]; then
        log "TLS certificate already exists in $CERT_DIR"
        return
    fi

    need_cmd openssl
    log "generating self-signed TLS certificate with openssl (valid ${CERT_VALID_DAYS} days)..."
    install -d -m 0750 "$CERT_DIR"

    local san_list
    san_list="$(build_san_list)"
    local cn="${DOMAIN:-$PUBLIC_NAME}"

    openssl req -x509 -nodes \
        -newkey rsa:2048 \
        -days "$CERT_VALID_DAYS" \
        -keyout "$key" \
        -out "$cert" \
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
    log "certificate written to $CERT_DIR"
}

start_services() {
    systemctl daemon-reload
    systemctl enable vmessenger-node
    systemctl restart vmessenger-node
    nginx -t
    systemctl reload nginx
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
    local base="https://${PUBLIC_NAME}"

    cat <<EOF

vMessenger node is running (self-signed TLS via openssl).

  Health:  ${base}/healthz
  DHT:     wss://${PUBLIC_NAME}/dht
  Relay:   wss://${PUBLIC_NAME}/relay

TLS: ${CERT_DIR}/fullchain.pem (self-signed)

Service: systemctl status vmessenger-node
Logs:    journalctl -u vmessenger-node -f
EOF

    print_terminal_qr "Scan in app (تنظیمات → نودهای شبکه → اسکن QR) — bootstrap:" "$bootstrap_link"
    print_terminal_qr "Scan in app — relay:" "$relay_link"
    print_terminal_qr "One-line install script (share to deploy another node):" "$install_one_liner"
    printf '\n%s\n' "$install_one_liner"
}

setup_production() {
    find_repo_root
    clone_repo_if_needed
    install_os_packages
    build_node
    create_system_user
    install_node_files
    write_systemd_unit
    generate_tls_certificate
    write_nginx_config
    start_services
    print_success
}

main() {
    parse_args "$@"
    require_root_for_prod
    validate_args
    if [[ "$MODE" == "dev" ]]; then
        run_dev_node
    else
        setup_production
    fi
}

main "$@"
