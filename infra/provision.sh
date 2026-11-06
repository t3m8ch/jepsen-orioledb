#!/usr/bin/env bash
set -euo pipefail

release=${1:?Debian release required}
version=${2:?Debian version required}
snapshot=${3:?APT snapshot required}
runtime_packages=${4:?Runtime packages required}

. /etc/os-release
[[ $ID == debian && $VERSION_ID == "$version" && $VERSION_CODENAME == "$release" ]]
[[ $(dpkg --print-architecture) == amd64 ]]

export DEBIAN_FRONTEND=noninteractive
cat > /etc/apt/apt.conf.d/99-jepsen <<'APTCONF'
Acquire::Languages "none";
Acquire::http::Timeout "30";
APTCONF

# This is a disposable lab VM; all package sources use the same immutable snapshot.
rm -f /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources
cat > /etc/apt/sources.list <<APT
deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/$snapshot/ $release main
deb [check-valid-until=no] http://snapshot.debian.org/archive/debian-security/$snapshot/ $release-security main
APT

apt-get -o Acquire::Retries=3 update
read -r -a packages <<< "$runtime_packages"

apt-get -o Acquire::Retries=3 install -y --no-install-recommends "${packages[@]}" sudo openssh-server \
  ca-certificates curl wget tar gzip bzip2 unzip procps psmisc iproute2 iptables util-linux \
  coreutils findutils chrony build-essential ntpdate netcat-openbsd

id postgres &>/dev/null || useradd --system --create-home --home-dir /var/lib/postgresql --shell /bin/bash postgres
install -d -o postgres -g postgres -m 0755 /opt/jepsen /var/lib/jepsen /var/log/jepsen

# Vagrant rotates and manages this user's key; Jepsen uses its actual ssh-config.
printf 'vagrant ALL=(ALL:ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/jepsen-vagrant
chmod 0440 /etc/sudoers.d/jepsen-vagrant
visudo -cf /etc/sudoers.d/jepsen-vagrant

cat > /etc/security/limits.d/jepsen.conf <<'LIMITS'
postgres soft nofile 65536
postgres hard nofile 65536
LIMITS

# No packaged PostgreSQL and no service competing with the Jepsen adapter.
systemctl enable --now ssh chrony
