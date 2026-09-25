#!/usr/bin/env bash
#
# Throwaway servers for testing the node installer (scripts/setup-node.sh), in Docker on this machine.
#
# Every target is a privileged container running systemd and sshd, reachable only on 127.0.0.1 (an
# Android emulator sees that as 10.0.2.2). Nothing here ever connects to a real server.
#
#   scripts/provision-test/run.sh build [IMAGE...]      build the target images
#   scripts/provision-test/run.sh up IMAGE [--slot N]   start one target and print how to reach it
#   scripts/provision-test/run.sh install IMAGE [--slot N]  the same, with a node installed; prints its pin
#   scripts/provision-test/run.sh down [NAME...|--all]  remove targets
#   scripts/provision-test/run.sh test [options]        run scenarios against fresh targets
#   scripts/provision-test/run.sh creds                 print the throwaway credentials
#   scripts/provision-test/run.sh shell NAME            a root shell inside a target
#
# test options:
#   --images "ubuntu:22.04 debian:12"   default: the supported matrix
#   --unsupported                        the images the installer must refuse (18.04, Debian 10)
#   --scenario NAME                      default: happy-ip (see `scenarios` below)
#   --keep                               leave targets running afterwards
#   --parallel N                         targets at once (default 1)
#   --platform linux/amd64               run under emulation instead of the host architecture
#
# Credentials (keys, a password) are generated into scripts/provision-test/out/ on first use. That
# directory is git-ignored; the credentials open nothing but these containers.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/out"
NET="vm-provision-net"
LABEL="ir.vmessenger.provision-test=1"
SUPPORTED_IMAGES="ubuntu:20.04 ubuntu:22.04 ubuntu:24.04 ubuntu:26.04 debian:11 debian:12 debian:13"
UNSUPPORTED_IMAGES="ubuntu:18.04 debian:10"
PORT_BASE=22000
PLATFORM=""

log() { printf '\033[1m==> %s\033[0m\n' "$*" >&2; }
fail() { printf 'provision-test: %s\n' "$*" >&2; exit 1; }

image_tag() { printf 'vm-provision:%s' "$(printf '%s' "$1" | tr ':/' '--')"; }
target_name() { printf 'vm-target-%s' "$(printf '%s' "$1" | tr ':/.' '---')"; }

# Slot N owns ports PORT_BASE+10N+{2,0,3}: ssh, http, https. Bound to 127.0.0.1 only.
ssh_port() { printf '%s' $((PORT_BASE + 10 * $1 + 2)); }
http_port() { printf '%s' $((PORT_BASE + 10 * $1)); }
https_port() { printf '%s' $((PORT_BASE + 10 * $1 + 3)); }

ensure_creds() {
    mkdir -p "$OUT"
    chmod 0700 "$OUT"
    local who
    for who in alice carol root; do
        [[ -f "$OUT/${who}_ed25519" ]] || ssh-keygen -q -t ed25519 -N '' -C "$who@vm-provision-test" -f "$OUT/${who}_ed25519"
    done
    # A passphrase-protected key and an RSA key for alice, to exercise the app's key reader.
    [[ -f "$OUT/alice_enc.passphrase" ]] || openssl rand -hex 12 > "$OUT/alice_enc.passphrase"
    [[ -f "$OUT/alice_ed25519_enc" ]] \
        || ssh-keygen -q -t ed25519 -N "$(cat "$OUT/alice_enc.passphrase")" -C alice-enc -f "$OUT/alice_ed25519_enc"
    [[ -f "$OUT/alice_rsa" ]] || ssh-keygen -q -t rsa -b 3072 -m PEM -N '' -C alice-rsa -f "$OUT/alice_rsa"
    [[ -f "$OUT/bob.password" ]] || openssl rand -hex 10 > "$OUT/bob.password"
}

ensure_network() {
    docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
}

build_image() {
    local base="$1" tag args=()
    tag="$(image_tag "$base")"
    [[ -n "$PLATFORM" ]] && args+=(--platform "$PLATFORM") && tag="$tag-$(printf '%s' "$PLATFORM" | tr '/' '-')"
    if docker image inspect "$tag" >/dev/null 2>&1; then
        printf '%s' "$tag"
        return
    fi
    log "building $tag from $base"
    docker build -q ${args[@]+"${args[@]}"} --build-arg "BASE=$base" -t "$tag" "$HERE" >/dev/null
    printf '%s' "$tag"
}

# up IMAGE SLOT — start a target, install the credentials, wait for systemd and sshd.
up() {
    local base="$1" slot="$2" tag name
    ensure_creds
    ensure_network
    tag="$(build_image "$base")"
    name="$(target_name "$base")"
    docker rm -f "$name" >/dev/null 2>&1 || true
    local args=(-d --name "$name" --hostname "${name#vm-target-}" --label "$LABEL" --network "$NET"
        --privileged --cgroupns=private --tmpfs /run --tmpfs /run/lock
        -p "127.0.0.1:$(ssh_port "$slot"):22" -p "127.0.0.1:$(http_port "$slot"):80"
        -p "127.0.0.1:$(https_port "$slot"):443")
    [[ -n "$PLATFORM" ]] && args+=(--platform "$PLATFORM")
    if is_unsupported "$base"; then
        # systemd 237 and older cannot boot on a cgroup v2 host (Docker Desktop). The installer
        # refuses these releases before it needs systemd, so sshd on its own is enough.
        docker run "${args[@]}" "$tag" bash -c 'ssh-keygen -A >/dev/null; mkdir -p /run/sshd; exec /usr/sbin/sshd -D' >/dev/null || return 1
    else
        docker run "${args[@]}" "$tag" >/dev/null || return 1
        wait_for_systemd "$name" || return 1
    fi
    install_creds "$name" || return 1
    wait_for_ssh "$slot" || return 1
    printf '%s' "$name"
}

is_unsupported() { [[ " $UNSUPPORTED_IMAGES " == *" $1 "* ]]; }

wait_for_systemd() {
    local name="$1" state
    for _ in $(seq 1 60); do
        state="$(docker exec "$name" systemctl is-system-running 2>/dev/null || true)"
        case "$state" in
            running|degraded) return 0 ;;
        esac
        sleep 1
    done
    docker exec "$name" systemctl --failed --no-pager >&2 || true
    fail "$name: systemd did not finish booting (state: ${state:-none})"
}

install_creds() {
    local name="$1" who home
    for who in alice carol root; do
        home="/home/$who"
        [[ "$who" == root ]] && home=/root
        docker exec "$name" install -d -m 0700 -o "$who" -g "$who" "$home/.ssh"
        {
            cat "$OUT/${who}_ed25519.pub"
            if [[ "$who" == alice ]]; then cat "$OUT/alice_ed25519_enc.pub" "$OUT/alice_rsa.pub"; fi
        } | docker exec -i "$name" sh -c "cat > $home/.ssh/authorized_keys && chown $who:$who $home/.ssh/authorized_keys && chmod 0600 $home/.ssh/authorized_keys"
    done
    printf 'bob:%s\n' "$(cat "$OUT/bob.password")" | docker exec -i "$name" chpasswd
}

ssh_opts() {
    printf '%s\n' -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
        -o ConnectTimeout=5 -o BatchMode=yes
}

# on SLOT USER CMD... — run a command on a target over real SSH, as a client would.
on() {
    local slot="$1" user="$2"
    shift 2
    local opts=()
    while IFS= read -r line; do opts+=("$line"); done < <(ssh_opts)
    ssh "${opts[@]}" -i "$OUT/${user}_ed25519" -p "$(ssh_port "$slot")" "$user@127.0.0.1" "$@"
}

copy_to() {
    local slot="$1" user="$2" dest="$3"
    shift 3
    local opts=()
    while IFS= read -r line; do opts+=("$line"); done < <(ssh_opts)
    scp -q -r "${opts[@]}" -i "$OUT/${user}_ed25519" -P "$(ssh_port "$slot")" "$@" "$user@127.0.0.1:$dest"
}

wait_for_ssh() {
    local slot="$1"
    for _ in $(seq 1 30); do
        on "$slot" alice true 2>/dev/null && return 0
        sleep 1
    done
    fail "sshd on 127.0.0.1:$(ssh_port "$slot") did not answer"
}

node_tarball() {
    local version tar
    version="$(sed -n 's/^versionName=//p' "$REPO/gradle/version.properties")"
    tar="$REPO/node/build/distributions/vmessenger-node-$version.tar.gz"
    [[ -f "$tar" ]] || fail "no $tar — run ./gradlew :node:distTar first"
    printf '%s' "$tar"
}

node_version() { sed -n 's/^versionName=//p' "$REPO/gradle/version.properties"; }

# The bundle the app uploads: installer, templates, node tarball, manifest.json and SHA256SUMS.
make_bundle() {
    local dir="$OUT/bundle" tar
    tar="$(node_tarball)"
    rm -rf "$dir"
    mkdir -p "$dir"
    cp "$REPO/scripts/setup-node.sh" "$tar" "$dir/"
    cp -R "$REPO/deploy" "$dir/deploy"
    printf '{"protocol": 1, "nodeVersion": "%s"}\n' "$(node_version)" > "$dir/manifest.json"
    (cd "$dir" && find . -type f | sed 's|^\./||' | sort | xargs shasum -a 256 > "$OUT/SHA256SUMS" && mv "$OUT/SHA256SUMS" .)
    printf '%s' "$dir"
}

# stage_bundle SLOT USER — upload the bundle to ~/.vmessenger-installer/<version>/ like the app does;
# prints the remote path.
stage_bundle() {
    local slot="$1" user="$2" bundle remote
    bundle="$(make_bundle)" || return 1
    remote=".vmessenger-installer/$(node_version)"
    on "$slot" "$user" "rm -rf $remote && mkdir -p .vmessenger-installer" || return 1
    copy_to "$slot" "$user" "$remote" "$bundle" || return 1
    printf '/home/%s/%s' "$user" "$remote"
}

# stage SLOT — the installer, its templates and the node tarball into alice's ~/vm, the way a person
# following the manual would.
stage() {
    local slot="$1" tar
    tar="$(node_tarball)" || return 1
    on "$slot" alice 'rm -rf ~/vm && mkdir -p ~/vm/scripts' || return 1
    copy_to "$slot" alice 'vm/scripts/' "$REPO/scripts/setup-node.sh" || return 1
    copy_to "$slot" alice 'vm/' "$REPO/deploy" "$tar"
}

# ---- scenarios -------------------------------------------------------------------------------------
# Each takes (image, slot, name) and returns non-zero on failure. They talk to the target only over
# SSH and the mapped ports, like the app would.

# The app's path: preflight, launch detached, follow to the end, read the result.
scenario_happy_ip() {
    local slot="$2" log="$OUT/$3" b run status=0
    b="$(stage_bundle "$slot" alice)" || return 1
    local installer="sudo bash $b/setup-node.sh --from-app --bundle-dir $b"
    local extra="${ALLOW_ARGS:-}"
    on "$slot" alice "$installer --preflight --ip 127.0.0.1 $extra" > "$log.preflight" 2>&1 \
        || { cat "$log.preflight" >&2; return 1; }
    grep -q '^##vm .* ev=end status=0$' "$log.preflight" || { cat "$log.preflight" >&2; return 1; }
    on "$slot" alice "$installer --launch --ip 127.0.0.1 $extra" > "$log.launch" 2>&1 || { cat "$log.launch" >&2; return 1; }
    run="$(sed -n 's/^##vm .* ev=launched run=\([0-9a-f-]*\).*/\1/p' "$log.launch")"
    [[ -n "$run" ]] || { cat "$log.launch" >&2; return 1; }
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --follow $run" > "$log.follow" 2>&1 || status=$?
    [[ "$status" -eq 0 ]] || { tail -40 "$log.follow" >&2; return 1; }
    tail -n 1 "$log.follow" | grep -q 'ev=end status=0$' || { tail -5 "$log.follow" >&2; return 1; }
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --result $run" > "$log.result.json"
    grep -q '"status": "ok"' "$log.result.json" || { cat "$log.result.json" >&2; return 1; }
    check_markers "$log.follow" || return 1
    check_health "$slot"
}

# Every marker line parses, seq has no gaps, and every started step ends.
check_markers() {
    local file="$1"
    awk '
        /^##vm / {
            if ($2 != "v=1" || $3 !~ /^seq=[0-9]+$/ || $4 !~ /^ts=[0-9]+$/ || $5 !~ /^ev=[a-z-]+$/) { print "bad marker: " $0; bad = 1 }
            n = substr($3, 5) + 0
            if (n != last + 1) { print "seq gap before: " $0; bad = 1 }
            last = n
            if ($5 == "ev=step") {
                id = ""; state = ""
                for (i = 6; i <= NF; i++) { if ($i ~ /^id=/) id = substr($i, 4); if ($i ~ /^state=/) state = substr($i, 7) }
                if (state == "start") open[id] = 1; else delete open[id]
            }
        }
        END { for (id in open) { print "step never ended: " id; bad = 1 } exit bad }
    ' "$file"
}

# Human mode, from a copy of the repo files: what docs/Deployment.md §3.1 describes.
scenario_happy_ip_human() {
    local slot="$2" tar
    tar="$(basename "$(node_tarball)")"
    stage "$slot" || return 1
    on "$slot" alice "sudo VMESSENGER_REPO=\$HOME/vm bash \$HOME/vm/scripts/setup-node.sh --ip 127.0.0.1 --dist-tar \$HOME/vm/$tar" \
        > "$OUT/$3.install.log" 2>&1 || { tail -30 "$OUT/$3.install.log" >&2; return 1; }
    check_health "$slot"
}

scenario_not_root() {
    local slot="$2" status=0
    stage "$slot" || return 1
    on "$slot" carol 'true' || return 1
    # carol has no sudo; running the installer as her must stop with NOT_ROOT (exit 30).
    copy_to "$slot" carol '' "$REPO/scripts/setup-node.sh"
    on "$slot" carol 'bash ~/setup-node.sh --ip 127.0.0.1' > "$OUT/$3.install.log" 2>&1 || status=$?
    [[ "$status" -eq 30 ]] || { cat "$OUT/$3.install.log" >&2; printf 'expected exit 30, got %s\n' "$status" >&2; return 1; }
    grep -q 'NOT_ROOT' "$OUT/$3.install.log"
}

# A dropped connection mid-run: the install carries on, and --follow --from-byte picks the log up
# again with nothing lost or repeated.
scenario_resume() {
    local slot="$2" log="$OUT/$3" b run size
    b="$(stage_bundle "$slot" alice)" || return 1
    local installer="sudo bash $b/setup-node.sh --from-app --bundle-dir $b"
    on "$slot" alice "$installer --launch --ip 127.0.0.1" > "$log.launch" 2>&1 || { cat "$log.launch" >&2; return 1; }
    run="$(sed -n 's/^##vm .* ev=launched run=\([0-9a-f-]*\).*/\1/p' "$log.launch")"
    [[ -n "$run" ]] || return 1
    # First connection: follow for a few seconds, then drop it.
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --follow $run" > "$log.part1" 2>&1 &
    local pid=$!
    sleep 8
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    size="$(wc -c < "$log.part1" | tr -d ' ')"
    # Keep whole lines only, as the app does: resume from the end of the last complete line.
    size="$(head -c "$size" "$log.part1" | awk '{ n += length($0) + 1 } END { print n + 0 }')"
    head -c "$size" "$log.part1" > "$log.part1.lines"
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --follow $run --from-byte $size" > "$log.part2" 2>&1 \
        || { tail -20 "$log.part2" >&2; return 1; }
    cat "$log.part1.lines" "$log.part2" > "$log.joined"
    on "$slot" alice "sudo cat /var/lib/vmessenger-installer/runs/$run/log" > "$log.server"
    cmp -s "$log.joined" "$log.server" || { printf 'resumed log differs from the server copy\n' >&2; return 1; }
    check_markers "$log.joined"
}

# A second launch while one is running answers INSTALL_BUSY (exit 40) with the active run's id.
scenario_busy() {
    local slot="$2" log="$OUT/$3" b first status=0
    b="$(stage_bundle "$slot" alice)" || return 1
    local installer="sudo bash $b/setup-node.sh --from-app --bundle-dir $b"
    on "$slot" alice "$installer --launch --ip 127.0.0.1" > "$log.first" 2>&1 || return 1
    first="$(sed -n 's/^##vm .* ev=launched run=\([0-9a-f-]*\).*/\1/p' "$log.first")"
    on "$slot" alice "$installer --launch --ip 127.0.0.1" > "$log.second" 2>&1 || status=$?
    [[ "$status" -eq 40 ]] || { cat "$log.second" >&2; printf 'expected 40, got %s\n' "$status" >&2; return 1; }
    grep -q "ev=fact key=active_run value=$first" "$log.second" || { cat "$log.second" >&2; return 1; }
    grep -q 'ev=issue code=INSTALL_BUSY' "$log.second" || return 1
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --follow $first" > "$log.follow" 2>&1
}

# ---- things that go wrong on real servers, set up by hand as root before the app arrives ----------

# as_root SLOT CMD — change the target the way its owner (or its history) would have.
as_root() { on "$1" root "$2"; }

# install_and_expect SLOT LABEL ISSUE... — the machine-mode install succeeds and reported each ISSUE.
install_and_expect() {
    local slot="$1" label="$2"
    shift 2
    scenario_happy_ip "" "$slot" "$label" || return 1
    local code
    for code in "$@"; do
        grep -q "ev=issue code=$code " "$OUT/$label.follow" \
            || { printf 'expected issue %s in the run\n' "$code" >&2; return 1; }
    done
}

# A third-party repository that no longer answers makes `apt-get update` fail on most servers
# someone has used for a while. The install leaves it out and goes on.
scenario_broken_repo() {
    as_root "$2" "echo 'deb [trusted=yes] http://127.0.0.1:9/gone stable main' > /etc/apt/sources.list.d/gone.list" || return 1
    install_and_expect "$2" "$3" APT_REPO_EXCLUDED || return 1
    as_root "$2" 'test -f /etc/apt/sources.list.d/gone.list'   # the owner's file is untouched
}

# A package left unpacked but not configured (a reboot mid-upgrade) blocks every apt install until
# `dpkg --configure -a` runs.
scenario_dpkg_interrupted() {
    # shellcheck disable=SC2016 # expanded on the target, not here
    as_root "$2" 'deb=$(ls /var/cache/apt/archives/rsync_*.deb 2>/dev/null | head -n 1); [ -n "$deb" ] || { apt-get update -qq && apt-get download -qq rsync && deb=$(ls rsync_*.deb); }; dpkg --unpack "$deb" >/dev/null && test -n "$(dpkg --audit)"' \
        || return 1
    install_and_expect "$2" "$3" DPKG_INTERRUPTED
}

# Another package manager holds the lock when the install starts (unattended-upgrades on a fresh
# VPS): the install waits for it instead of failing.
scenario_apt_lock() {
    as_root "$2" 'systemd-run --unit=vm-test-holds-dpkg-lock --collect flock /var/lib/dpkg/lock-frontend sleep 25' || return 1
    install_and_expect "$2" "$3" || return 1
    grep -q 'ev=step id=apt state=wait' "$OUT/$3.follow" || { printf 'the install never waited for the lock\n' >&2; return 1; }
}

# The mirror in the server's sources does not answer (blocked, or gone): the install finds one that
# does, uses it for its own apt calls, and leaves the server's sources as they were.
scenario_mirror_unreachable() {
    as_root "$2" "sed -i -E 's#https?://(archive|ports|security)\\.ubuntu\\.com#http://127.0.0.1:9#g; s#https?://(deb|security)\\.debian\\.org#http://127.0.0.1:9#g' /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null; grep -rq '127.0.0.1:9' /etc/apt/" \
        || return 1
    install_and_expect "$2" "$3" APT_MIRROR_SWITCHED || return 1
    as_root "$2" "grep -rq '127.0.0.1:9' /etc/apt/"   # the server's sources were not edited
}

# A server clock minutes off (as the app measures it) needs the owner's go-ahead before it is
# changed: preflight stops with exit 10 and a CLOCK_SKEW decision. The go-ahead path is not run
# here: setting the clock inside a privileged container sets the Docker VM's.
scenario_clock_skew() {
    local slot="$2" log="$OUT/$3" b status=0
    b="$(stage_bundle "$slot" alice)" || return 1
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --bundle-dir $b --preflight --ip 127.0.0.1 --clock-offset-ms 900000" \
        > "$log" 2>&1 || status=$?
    if [[ "$status" -ne 10 ]] || ! grep -q 'ev=issue code=CLOCK_SKEW severity=consent' "$log"; then
        cat "$log" >&2
        return 1
    fi
}

# A release newer than the tested ones asks first (exit 10, OS_UNTESTED) and installs once allowed.
# There is no such release to pull, so the target's os-release claims one (run on ubuntu:24.04).
scenario_untested_os() {
    local slot="$2" log="$OUT/$3" b status=0
    as_root "$slot" "sed -i -e 's/^VERSION_ID=.*/VERSION_ID=\"28.04\"/' -e 's/^PRETTY_NAME=.*/PRETTY_NAME=\"Ubuntu 28.04 LTS\"/' /etc/os-release" || return 1
    b="$(stage_bundle "$slot" alice)" || return 1
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --bundle-dir $b --preflight --ip 127.0.0.1" > "$log.preflight" 2>&1 \
        || status=$?
    if [[ "$status" -ne 10 ]] || ! grep -q 'ev=issue code=OS_UNTESTED severity=consent' "$log.preflight"; then
        cat "$log.preflight" >&2
        return 1
    fi
    ALLOW_ARGS="--allow OS_UNTESTED" scenario_happy_ip "" "$slot" "$3.allowed"
}

# An OS the installer does not support is refused at preflight: exit 20, OS_UNSUPPORTED, nothing
# installed. Run with --unsupported.
scenario_unsupported_os() {
    local slot="$2" log="$OUT/$3" b status=0
    b="$(stage_bundle "$slot" alice)" || return 1
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --bundle-dir $b --preflight --ip 127.0.0.1" > "$log" 2>&1 \
        || status=$?
    if [[ "$status" -ne 20 ]] || ! grep -q 'ev=issue code=OS_UNSUPPORTED severity=fatal' "$log"; then
        cat "$log" >&2
        printf 'expected exit 20 with OS_UNSUPPORTED, got %s\n' "$status" >&2
        return 1
    fi
    ! on "$slot" alice 'command -v nginx' >/dev/null 2>&1
}

# A bundle that does not match its SHA256SUMS is refused before anything is installed.
scenario_corrupt_bundle() {
    local slot="$2" log="$OUT/$3" b status=0
    b="$(stage_bundle "$slot" alice)" || return 1
    on "$slot" alice "printf 'x' >> $b/deploy/nginx/vmessenger-node.conf.template"
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --bundle-dir $b --launch --ip 127.0.0.1" > "$log" 2>&1 || status=$?
    if [[ "$status" -ne 1 ]] || ! grep -q 'ev=issue code=BUNDLE_CORRUPT' "$log"; then
        cat "$log" >&2
        return 1
    fi
}

check_health() {
    local slot="$1" body
    body="$(curl -sk -m 10 "https://127.0.0.1:$(https_port "$slot")/healthz" || true)"
    [[ "$body" == ok* ]] || { printf 'https healthz answered: %s\n' "${body:-nothing}" >&2; return 1; }
    # The relay must upgrade to a WebSocket through nginx.
    local code
    code="$(curl -sk -m 10 -o /dev/null -w '%{http_code}' --http1.1 -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
        -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
        "https://127.0.0.1:$(https_port "$slot")/relay" || true)"
    [[ "$code" == 101 ]] || { printf '/relay answered %s, not 101\n' "$code" >&2; return 1; }
}

run_one() {
    local base="$1" slot="$2" scenario="$3" name fn status=0
    fn="scenario_$(printf '%s' "$scenario" | tr '-' '_')"
    declare -F "$fn" >/dev/null || fail "unknown scenario: $scenario"
    local label="$scenario@$base"
    # Scenarios run under `|| status=…`, where bash switches `set -e` off: every step that can fail
    # is checked by hand, starting here.
    if ! name="$(up "$base" "$slot")"; then
        printf 'FAIL %s (target did not come up)\n' "$label" | tee -a "$OUT/results.txt"
        return 1
    fi
    log "$label: running on $name (ssh 127.0.0.1:$(ssh_port "$slot"))"
    "$fn" "$base" "$slot" "$(printf '%s' "$label" | tr ':@/' '---')" || status=$?
    if [[ "$status" -eq 0 ]]; then
        printf 'PASS %s\n' "$label" | tee -a "$OUT/results.txt"
    else
        printf 'FAIL %s\n' "$label" | tee -a "$OUT/results.txt"
        docker exec "$name" journalctl -u vmessenger-node -n 20 --no-pager > "$OUT/$name.journal" 2>&1 || true
    fi
    [[ "$KEEP" == true ]] || docker rm -f "$name" >/dev/null 2>&1 || true
    return "$status"
}

cmd_test() {
    local images="$SUPPORTED_IMAGES" scenario="happy-ip" parallel=1
    KEEP=false
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --images) images="$2"; shift 2 ;;
            --unsupported) images="$UNSUPPORTED_IMAGES"; shift ;;
            --scenario) scenario="$2"; shift 2 ;;
            --keep) KEEP=true; shift ;;
            --parallel) parallel="$2"; shift 2 ;;
            --platform) PLATFORM="$2"; shift 2 ;;
            *) fail "unknown test option: $1" ;;
        esac
    done
    ensure_creds
    : > "$OUT/results.txt"
    local slot=0 pids=() base failed=0
    for base in $images; do
        if [[ "$parallel" -gt 1 ]]; then
            run_one "$base" "$slot" "$scenario" &
            pids+=($!)
            if [[ "${#pids[@]}" -ge "$parallel" ]]; then
                wait "${pids[0]}" || failed=$((failed + 1))
                pids=("${pids[@]:1}")
            fi
        else
            run_one "$base" "$slot" "$scenario" || failed=$((failed + 1))
        fi
        slot=$((slot + 1))
    done
    local pid
    for pid in "${pids[@]:-}"; do
        [[ -n "$pid" ]] && { wait "$pid" || failed=$((failed + 1)); }
    done
    printf '\n'
    cat "$OUT/results.txt"
    [[ "$failed" -eq 0 ]]
}

cmd_up() {
    local base="${1:-}" slot=0
    [[ -n "$base" ]] || fail "usage: run.sh up IMAGE [--slot N] [--platform P]"
    shift
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --slot) slot="$2"; shift 2 ;;
            --platform) PLATFORM="$2"; shift 2 ;;
            *) fail "unknown up option: $1" ;;
        esac
    done
    local name
    name="$(up "$base" "$slot")"
    cat <<EOF

$name is up ($base).
  From this Mac:     ssh -p $(ssh_port "$slot") -i $OUT/alice_ed25519 alice@127.0.0.1
  From an emulator:  host 10.0.2.2, SSH port $(ssh_port "$slot")
  HTTPS / HTTP:      127.0.0.1:$(https_port "$slot") / 127.0.0.1:$(http_port "$slot")
  Accounts:          run.sh creds
  Remove:            run.sh down $name
EOF
}

# install IMAGE [--slot N] — a target with a node on it, installed the app's way; prints how to reach
# the node and its key pin (the certificate is self-signed on an IP, so the pin is the identity).
cmd_install() {
    local base="${1:-}" slot=0 name b run
    [[ -n "$base" ]] || fail "usage: run.sh install IMAGE [--slot N]"
    shift
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --slot) slot="$2"; shift 2 ;;
            --platform) PLATFORM="$2"; shift 2 ;;
            *) fail "unknown install option: $1" ;;
        esac
    done
    name="$(up "$base" "$slot")" || fail "$base did not come up"
    b="$(stage_bundle "$slot" alice)" || fail "could not upload the bundle"
    run="$(on "$slot" alice "sudo bash $b/setup-node.sh --from-app --bundle-dir $b --launch --ip 127.0.0.1" \
        | sed -n 's/^##vm .* ev=launched run=\([0-9a-f-]*\).*/\1/p')"
    [[ -n "$run" ]] || fail "the install did not start"
    on "$slot" alice "sudo bash $b/setup-node.sh --from-app --follow $run" > "$OUT/$name.install.log" 2>&1 \
        || { tail -20 "$OUT/$name.install.log" >&2; fail "the install failed (log: $OUT/$name.install.log)"; }
    local pin port
    port="$(https_port "$slot")"
    pin="$(openssl s_client -connect "127.0.0.1:$port" </dev/null 2>/dev/null | openssl x509 -pubkey -noout \
        | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
    cat <<EOF2

A node is running on $name ($base).
  Key pin:            $pin
  From an emulator:   wss://10.0.2.2:$port/relay#pin-sha256=$pin
                      wss://10.0.2.2:$port/dht#pin-sha256=$pin
  From this Mac:      https://127.0.0.1:$port/healthz (curl -k)
  Node log:           run.sh shell $name, then journalctl -u vmessenger-node -f
EOF2
}

cmd_down() {
    if [[ "${1:-}" == "--all" || $# -eq 0 ]]; then
        docker ps -aq --filter "label=$LABEL" --filter "name=vm-target-" | xargs -r docker rm -f >/dev/null
        return
    fi
    docker rm -f "$@" >/dev/null
}

cmd_creds() {
    ensure_creds
    cat <<EOF
Throwaway credentials for the provision-test containers (they open nothing else):
  alice  key $OUT/alice_ed25519 (also alice_rsa, and alice_ed25519_enc with passphrase $(cat "$OUT/alice_enc.passphrase")); passwordless sudo
  bob    password $(cat "$OUT/bob.password"); sudo asks for the same password
  carol  key $OUT/carol_ed25519; no sudo
  root   key $OUT/root_ed25519
EOF
}

main() {
    command -v docker >/dev/null || fail "docker is not installed"
    docker info >/dev/null 2>&1 || fail "the docker daemon is not running"
    local cmd="${1:-}"
    [[ $# -gt 0 ]] && shift
    case "$cmd" in
        build)
            local base
            # shellcheck disable=SC2086 # the default list is word-split on purpose
            [[ $# -gt 0 ]] || set -- $SUPPORTED_IMAGES
            for base in "$@"; do build_image "$base" >/dev/null; done
            ;;
        up) cmd_up "$@" ;;
        install) cmd_install "$@" ;;
        down) cmd_down "$@" ;;
        test) cmd_test "$@" ;;
        creds) cmd_creds ;;
        shell) docker exec -it "${1:?target name}" bash ;;
        *) sed -n '3,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; [[ -z "$cmd" ]] || exit 1 ;;
    esac
}

main "$@"
