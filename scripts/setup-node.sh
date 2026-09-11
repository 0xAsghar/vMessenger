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
set -euo pipefail

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
SKIP_BUILD=false       # legacy: reuse node/build/install/node from the repo
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
ACTIVE_CERT_DIR=""
TLS_SUMMARY=""

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
  --skip-build          Reuse node/build/install/node from the repo (legacy)

Local development:
  --dev                 Run the TCP DHT node on :46555 (no nginx/systemd)

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
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

need_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

cleanup() {
    local dir
    for dir in "${TMP_DIRS[@]:-}"; do
        [[ -n "$dir" && -d "$dir" ]] && rm -rf "$dir"
    done
}
trap cleanup EXIT

make_tmp_dir() {
    local dir
    dir="$(mktemp -d /tmp/vmessenger-setup.XXXXXX)"
    TMP_DIRS+=("$dir")
    printf '%s' "$dir"
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --domain) DOMAIN="${2:-}"; shift 2 ;;
            --ip) PUBLIC_IP="${2:-}"; shift 2 ;;
            --tls) TLS_MODE="${2:-}"; shift 2 ;;
            --acme-email) ACME_EMAIL="${2:-}"; shift 2 ;;
            --acme-no-email) ACME_NO_EMAIL=true; shift ;;
            --behind-cdn) BEHIND_CDN="${2:-}"; shift 2 ;;
            --firewall) FIREWALL=true; shift ;;
            --dist-tar) DIST_TAR="${2:-}"; shift 2 ;;
            --dist-url) DIST_URL="${2:-}"; shift 2 ;;
            --build) BUILD_FROM_REPO=true; shift ;;
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
    if [[ -z "$TLS_MODE" ]]; then
        if [[ -n "$DOMAIN" ]]; then TLS_MODE="letsencrypt"; else TLS_MODE="selfsigned"; fi
    fi
    case "$TLS_MODE" in
        letsencrypt)
            [[ -n "$DOMAIN" ]] || die "--tls letsencrypt requires --domain"
            if [[ "$SKIP_CERT" == false && -z "$ACME_EMAIL" && "$ACME_NO_EMAIL" == false ]]; then
                die "--tls letsencrypt requires --acme-email EMAIL or --acme-no-email (or --skip-cert with certs already issued)"
            fi
            ;;
        selfsigned) ;;
        *) die "unknown --tls mode: $TLS_MODE (letsencrypt|selfsigned)" ;;
    esac
    case "$BEHIND_CDN" in
        none|arvan) ;;
        *) die "unknown --behind-cdn value: $BEHIND_CDN (none|arvan)" ;;
    esac
    if [[ -n "$DIST_TAR" && ! -f "$DIST_TAR" ]]; then
        die "--dist-tar not found: $DIST_TAR"
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

# Templates live in deploy/ inside the repo; the curl|bash path downloads them.
fetch_templates() {
    if [[ -n "$REPO_ROOT" && -f "$REPO_ROOT/deploy/nginx/vmessenger-node.conf.template" ]]; then
        TEMPLATE_DIR="$REPO_ROOT/deploy"
        return
    fi
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
    [[ -n "$REPO_ROOT" ]] || die "no repo root for build"
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
    [[ -x "$out/bin/node" ]] || die "tarball does not contain bin/node: $tar_path"
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

# Precedence: --dist-tar > --dist-url > --build > repo installDist (--skip-build)
# > latest GitHub release tarball.
acquire_dist() {
    if [[ -n "$DIST_TAR" ]]; then
        extract_tarball "$DIST_TAR"
        return
    fi
    if [[ -n "$DIST_URL" ]]; then
        download_dist "$DIST_URL"
        return
    fi
    if [[ "$BUILD_FROM_REPO" == true ]]; then
        clone_repo_if_needed
        build_node
        DIST_SOURCE_DIR="$REPO_ROOT/node/build/install/node"
        return
    fi
    if [[ "$SKIP_BUILD" == true && -n "$REPO_ROOT" && -x "$REPO_ROOT/node/build/install/node/bin/node" ]]; then
        log "using existing build at $REPO_ROOT/node/build/install/node"
        DIST_SOURCE_DIR="$REPO_ROOT/node/build/install/node"
        return
    fi
    local url
    url="$(latest_release_dist_url || true)"
    if [[ -z "$url" ]]; then
        die "no vmessenger-node tarball in the latest GitHub release — pass --dist-tar (./gradlew :node:distTar) or --build"
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
    [[ -x "$DIST_SOURCE_DIR/bin/node" ]] || die "node distribution not found at $DIST_SOURCE_DIR"
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
    [[ -f "$template" ]] || die "systemd template not found at $template"
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
        [[ -f "$src" ]] || die "arvan-ips.conf not found at $src"
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
    [[ -f "$template" ]] || die "nginx template not found at $template"
    log "writing nginx site $NGINX_SITE (certs: $cert_dir)"
    sed -e "s|__SERVER_NAME__|${SERVER_NAME}|g" \
        -e "s|__NODE_PORT__|${NODE_PORT}|g" \
        -e "s|__CERT_DIR__|${cert_dir}|g" \
        "$template" > "$NGINX_SITE"
    ln -sf "$NGINX_SITE" /etc/nginx/sites-enabled/vmessenger-node.conf
    rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true
    ACTIVE_CERT_DIR="$cert_dir"
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
                    || die "missing certificate in $CERT_DIR (need fullchain.pem and privkey.pem)"
            else
                generate_selfsigned_certificate
            fi
            write_nginx_config "$CERT_DIR"
            reload_nginx
            TLS_SUMMARY="self-signed (${CERT_DIR}); CDN must accept an untrusted origin cert, or clients must trust it"
            ;;
        letsencrypt)
            if [[ "$SKIP_CERT" == true ]]; then
                le_cert_exists || die "missing Let's Encrypt certificate in $(le_cert_dir)"
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
            die "node did not become healthy on 127.0.0.1:${NODE_PORT}"
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
    print_terminal_qr "Scan in app (تنظیمات → نودهای شبکه → اسکن QR) — bootstrap:" "$bootstrap_link"
    print_terminal_qr "Scan in app — relay:" "$relay_link"
    print_terminal_qr "One-line install script (share to deploy another node):" "$install_one_liner"
    printf '\n%s\n' "$install_one_liner"
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
    install_os_packages
    configure_firewall
    acquire_dist
    create_system_user
    create_dirs
    install_node_files
    remove_legacy_artifacts
    write_node_env
    write_systemd_unit
    write_realip_snippet
    start_node_service
    configure_tls_and_nginx
    health_check
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
