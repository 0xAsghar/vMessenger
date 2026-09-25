# A throwaway "VPS" for testing the node installer: systemd as PID 1, sshd, sudo, and the few
# tools a stock cloud image already has. Built per base image by run.sh; never pushed anywhere.
#
#   docker build --build-arg BASE=ubuntu:24.04 -t vm-provision:ubuntu-24.04 scripts/provision-test
ARG BASE=ubuntu:24.04
FROM ${BASE}

ENV DEBIAN_FRONTEND=noninteractive

# The packages the installer may ask for, downloaded but not installed. The install still runs apt
# for real; it just finds most .debs already in apt's archive cache instead of fetching ~150 MB per
# container over a slow uplink. Docker's base images delete that cache after every install
# (docker-clean), which a real server does not, so that goes too.
ARG PREFETCH="openjdk-21-jre-headless openjdk-17-jre-headless nginx openssl rsync qrencode certbot fail2ban unattended-upgrades systemd-timesyncd"

# EOL releases (Ubuntu 18.04, Debian 10) moved to the archive; the unsupported-OS scenarios still
# need an image, so point them there before the first update. Debian 11's security suite lists
# packages its pool no longer serves (end of LTS, 2026): the image is built from a snapshot of it,
# and its sources keep the live one, as a real Debian 11 server's do — which is what the installer
# has to cope with.
RUN . /etc/os-release; \
    if [ "$ID" = debian ] && [ "${VERSION_ID:-0}" -le 10 ]; then \
        sed -i -e 's|deb.debian.org|archive.debian.org|g' -e 's|security.debian.org|archive.debian.org|g' \
            -e '/-updates/d' /etc/apt/sources.list; \
    fi; \
    rm -f /etc/apt/apt.conf.d/docker-clean; \
    echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80retries; \
    o=""; \
    if [ "$ID" = debian ] && [ "${VERSION_ID:-0}" = 11 ]; then \
        grep -v security /etc/apt/sources.list > /etc/apt/image-build.list; \
        echo 'deb [check-valid-until=no] http://snapshot.debian.org/archive/debian-security/20260815T000000Z bullseye-security main' \
            >> /etc/apt/image-build.list; \
        o="-o Dir::Etc::SourceList=/etc/apt/image-build.list -o Acquire::http::Timeout=120"; \
    fi; \
    apt-get $o update -qq \
    && apt-get $o install -y -qq --no-install-recommends \
        systemd systemd-sysv dbus openssh-server sudo ca-certificates iproute2 procps curl \
    && for p in $PREFETCH; do apt-get $o install -y -qq --download-only "$p" >/dev/null 2>&1 || true; done \
    && rm -rf /var/lib/apt/lists/* /etc/apt/image-build.list

# Units a container has no use for, or that would hang boot waiting for hardware and consoles.
# getty.target: systemd 245 (Ubuntu 20.04) adds a serial getty for hvc0 and holds boot at "starting"
# waiting for a console device a container never gets.
RUN systemctl mask systemd-networkd-wait-online.service getty@tty1.service console-getty.service \
        getty.target serial-getty@hvc0.service systemd-logind.service 2>/dev/null || true; \
    rm -f /etc/ssh/ssh_host_*; \
    mkdir -p /run/sshd

# Host keys are made on first boot, so every container — and every rebuild — has its own, the way a
# freshly created server does. The host-key mismatch scenario depends on that.
COPY hostkeys.service /etc/systemd/system/vm-test-hostkeys.service
RUN systemctl enable vm-test-hostkeys.service

# Test accounts. Keys and passwords are written by run.sh at start, from its out/ directory:
#   alice  key login, passwordless sudo       (the common case)
#   bob    password login, sudo asks for it   (sudo -S path)
#   carol  key login, no sudo                 (NOT_ROOT)
RUN for u in alice bob carol; do useradd -m -s /bin/bash "$u"; done; \
    echo 'alice ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/alice; \
    echo 'bob ALL=(ALL) ALL' > /etc/sudoers.d/bob; \
    chmod 0440 /etc/sudoers.d/alice /etc/sudoers.d/bob; \
    printf 'PasswordAuthentication yes\nPermitRootLogin prohibit-password\n' > /etc/ssh/sshd_config.d/10-vm-test.conf 2>/dev/null \
        || printf 'PasswordAuthentication yes\nPermitRootLogin prohibit-password\n' >> /etc/ssh/sshd_config

STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
