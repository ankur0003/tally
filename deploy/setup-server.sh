#!/usr/bin/env bash
# One-time server bootstrap. Runs ON the instance as root (deploy.sh --bootstrap does this for you).
# Idempotent: re-running only fills in what is missing and never overwrites an existing env file.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Run as root"; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -yq openjdk-21-jdk-headless unattended-upgrades
dpkg-reconfigure -fnoninteractive unattended-upgrades

# 2 GB box: a little swap keeps the JVM alive through instrument-dump parsing spikes.
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  grep -q /swapfile /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

id tally >/dev/null 2>&1 || useradd --system --home /var/lib/tally --shell /usr/sbin/nologin tally
install -d -o tally -g tally -m 700 /var/lib/tally
install -d -o root -g root -m 755 /opt/tally
install -d -o root -g root -m 700 /etc/tally

if [[ ! -f /etc/tally/tally.env ]]; then
  install -o root -g root -m 600 /tmp/tally-deploy/tally.env.example /etc/tally/tally.env
  sed -i "s/^TALLY_API_TOKEN=.*/TALLY_API_TOKEN=$(openssl rand -hex 24)/" /etc/tally/tally.env
  echo ">>> Created /etc/tally/tally.env - fill in the Kite and Anthropic keys: sudo nano /etc/tally/tally.env"
fi

install -o root -g root -m 644 /tmp/tally-deploy/tally.service /etc/systemd/system/tally.service
systemctl daemon-reload
systemctl enable tally >/dev/null
echo "Bootstrap done."
