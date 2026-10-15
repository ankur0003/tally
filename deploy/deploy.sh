#!/usr/bin/env bash
# Ships the source (~100 KB) to the Lightsail instance and builds the jar there. Building remotely avoids
# pushing a 30+ MB fat jar over a slow or flaky home/mobile uplink; the server pulls dependencies from
# Maven over AWS's network instead.
#
#   export TALLY_HOST=ubuntu@<static-ip> TALLY_SSH_KEY=~/.ssh/LightsailDefaultKey-ap-south-1.pem
#   ./deploy/deploy.sh --bootstrap   # first time: installs JDK, user, dirs, env file, systemd unit
#   ./deploy/deploy.sh               # every release after that
set -euo pipefail
cd "$(dirname "$0")/.."

: "${TALLY_HOST:?set TALLY_HOST=ubuntu@<static-ip>}"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ServerAliveInterval=15 -o ConnectTimeout=20)
[[ -n "${TALLY_SSH_KEY:-}" ]] && SSH_OPTS+=(-i "$TALLY_SSH_KEY")

# A restart stops trade management (target, trailing stop) for about a minute. Broker-side stops still protect
# positions, but don't redeploy over open AI trades unless explicitly forced.
if [[ "${TALLY_FORCE_DEPLOY:-}" != "1" ]]; then
  OPEN=$(ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'T=$(sudo grep ^TALLY_API_TOKEN= /etc/tally/tally.env | cut -d= -f2-);
    curl -s -m 10 -H "X-Tally-Token: $T" localhost:8090/api/ai/trader |
    python3 -c "import json,sys; t=json.load(sys.stdin); print(len(t.get(\"opens\", [])) + len(t.get(\"pendings\", [])) + (1 if t.get(\"open\") else 0))"' 2>/dev/null || echo 0)
  if [[ "${OPEN:-0}" != "0" ]]; then
    echo "Refusing to deploy: $OPEN AI trade(s) open or waiting. Close them first, or set TALLY_FORCE_DEPLOY=1."
    exit 1
  fi
fi

TARBALL=$(mktemp -t tally-src).tgz
trap 'rm -f "$TARBALL"' EXIT
COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata --exclude=build --exclude=.gradle --exclude=.idea --exclude=.kotlin --exclude=.code-review-graph -czf "$TARBALL" .

# Small upload, but still retried: mobile connections drop SSH sessions without warning.
for attempt in 1 2 3 4 5; do
  if ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'cat > /tmp/tally-src.tgz' < "$TARBALL"; then break; fi
  [[ $attempt == 5 ]] && { echo "Upload failed after 5 attempts"; exit 1; }
  echo "Upload interrupted, retrying ($attempt)..."; sleep 5
done
echo "Source uploaded ($(wc -c < "$TARBALL" | tr -d ' ') bytes)"

# Everything below runs on the server under nohup, so a dropped SSH session doesn't kill the build.
REMOTE_SCRIPT=$(cat <<EOF
set -euo pipefail
rm -rf ~/tally-src && mkdir -p ~/tally-src && tar -xzf /tmp/tally-src.tgz -C ~/tally-src
cd ~/tally-src
if [[ "${1:-}" == "--bootstrap" ]]; then
  mkdir -p /tmp/tally-deploy && cp deploy/tally.service deploy/tally.env.example /tmp/tally-deploy/
  sudo bash deploy/setup-server.sh
fi
# 2 GB box: cap Gradle's heap and compile in-process so we don't start a second Kotlin daemon JVM.
./gradlew buildFatJar --no-daemon --console=plain -q \
  -Dorg.gradle.jvmargs="-Xmx900m -XX:+UseSerialGC" -Pkotlin.compiler.execution.strategy=in-process
if [[ -f /opt/tally/tally.jar ]]; then sudo cp /opt/tally/tally.jar /opt/tally/tally.jar.prev; fi
sudo install -o root -g root -m 644 build/libs/tally.jar /opt/tally/tally.jar
sudo install -o root -g root -m 644 deploy/tally.service /etc/systemd/system/tally.service
sudo systemctl daemon-reload
if sudo grep -qE '^(KITE_API_KEY|KITE_API_SECRET)=\$' /etc/tally/tally.env; then
  echo ">>> Kite keys are empty in /etc/tally/tally.env. Fill them in, then: sudo systemctl restart tally"
fi
sudo systemctl restart tally
for i in \$(seq 1 30); do curl -fsS http://127.0.0.1:8090/health >/dev/null 2>&1 && break; sleep 2; done
if curl -fsS http://127.0.0.1:8090/health >/dev/null; then
  echo "tally is up (\$(sudo grep '^TALLY_MODE=' /etc/tally/tally.env))"
else
  echo "Health check failed. Last log lines:"; sudo journalctl -u tally -n 40 --no-pager; exit 1
fi
EOF
)
ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'cat > ~/tally-deploy.sh' <<< "$REMOTE_SCRIPT"
ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'rm -f ~/tally-deploy.log; nohup bash -c "bash ~/tally-deploy.sh; echo EXIT \$?" > ~/tally-deploy.log 2>&1 < /dev/null &'

# Follow the remote log; transient SSH failures just mean we poll again. The remote build keeps running.
echo "Building on server (first run downloads Gradle + dependencies, a few minutes)..."
seen=0
while true; do
  sleep 5
  out=$(ssh "${SSH_OPTS[@]}" "$TALLY_HOST" "tail -n +$((seen + 1)) ~/tally-deploy.log 2>/dev/null" || true)
  [[ -z "$out" ]] && continue
  printf '%s\n' "$out" | grep -vE '^(Get|Hit|Ign|Reading|Building|Unpacking|Selecting|Preparing|Setting up|Processing|Fetched|Created symlink|update-alternatives)|^\s*$|debconf|Scanning' || true
  seen=$((seen + $(printf '%s\n' "$out" | wc -l)))
  if code=$(printf '%s\n' "$out" | grep -oE '^EXIT [0-9]+' | tail -1) && [[ -n "$code" ]]; then
    exit "${code#EXIT }"
  fi
done
