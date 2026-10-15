#!/usr/bin/env bash
# Puts Caddy (automatic Let's Encrypt HTTPS) in front of tally and serves the whole dashboard on <domain>,
# behind a browser password (HTTP basic auth) on top of the app's own X-Tally-Token.
#
#   export TALLY_HOST=ubuntu@<static-ip> TALLY_SSH_KEY=~/.ssh/LightsailDefaultKey-ap-south-1.pem
#   ./deploy/setup-https.sh tally.example.com
#
# Before running: DNS A record for the domain -> static IP (DNS only, not proxied by Cloudflare),
# and the Lightsail firewall allows TCP 80 and 443 from any address.
#
# Afterwards set the dashboard password yourself (it never leaves your terminal):
#   ssh -t -i "$TALLY_SSH_KEY" "$TALLY_HOST" sudo tally-set-password
# Until then every page answers 401.
set -euo pipefail

DOMAIN="${1:?usage: $0 <domain>}"
: "${TALLY_HOST:?set TALLY_HOST=ubuntu@<static-ip>}"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ServerAliveInterval=15 -o ConnectTimeout=20)
[[ -n "${TALLY_SSH_KEY:-}" ]] && SSH_OPTS+=(-i "$TALLY_SSH_KEY")

SERVER_IP="${TALLY_HOST#*@}"
RESOLVED=$(dig +short A "$DOMAIN" @1.1.1.1 | tail -1)
if [[ "$RESOLVED" != "$SERVER_IP" ]]; then
  echo "DNS for $DOMAIN resolves to '${RESOLVED:-nothing}', expected $SERVER_IP."
  echo "Add an A record (DNS only / grey cloud) and wait for it to propagate, then re-run."
  exit 1
fi

ssh "${SSH_OPTS[@]}" "$TALLY_HOST" sudo bash -s -- "$DOMAIN" <<'EOF'
set -euo pipefail
DOMAIN="$1"
if ! command -v caddy >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get install -yq debian-keyring debian-archive-keyring apt-transport-https curl gnupg >/dev/null
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --batch --yes --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q >/dev/null
  apt-get install -yq caddy >/dev/null
fi

# Password helper: prompts on the terminal, stores only the bcrypt hash, reloads Caddy.
cat > /usr/local/bin/tally-set-password <<'HELPER'
#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo"; exit 1; }
read -rp "Dashboard username: " user
[[ "$user" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "letters, digits, . _ - only"; exit 1; }
echo "Choose a long password (12+ characters)."
hash=$(caddy hash-password)
printf '%s %s\n' "$user" "$hash" > /etc/caddy/tally_auth
chown root:caddy /etc/caddy/tally_auth && chmod 640 /etc/caddy/tally_auth
systemctl reload caddy
echo "Password set for '$user'."
HELPER
chmod 755 /usr/local/bin/tally-set-password

# Until the owner sets a password, lock the site with an account nobody knows.
if [[ ! -s /etc/caddy/tally_auth ]]; then
  printf 'locked %s\n' "$(caddy hash-password --plaintext "$(head -c 32 /dev/urandom | base64)")" > /etc/caddy/tally_auth
  chown root:caddy /etc/caddy/tally_auth && chmod 640 /etc/caddy/tally_auth
fi

cat > /etc/caddy/Caddyfile <<CADDY
$DOMAIN {
	basic_auth {
		import /etc/caddy/tally_auth
	}
	reverse_proxy 127.0.0.1:8090
	header {
		-Server
		Strict-Transport-Security "max-age=31536000"
		Referrer-Policy no-referrer
		X-Content-Type-Options nosniff
		X-Frame-Options DENY
	}
}
CADDY

caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
systemctl enable caddy >/dev/null
systemctl reload caddy 2>/dev/null || systemctl restart caddy

# Let the dashboard on this origin call the API (CORS), then restart tally to pick it up.
if grep -q '^TALLY_PUBLIC_HOST=' /etc/tally/tally.env; then
  sed -i "s/^TALLY_PUBLIC_HOST=.*/TALLY_PUBLIC_HOST=$DOMAIN/" /etc/tally/tally.env
else
  printf '\n# Hostname served by Caddy\nTALLY_PUBLIC_HOST=%s\n' "$DOMAIN" >> /etc/tally/tally.env
fi
systemctl restart tally
echo "Caddy configured for $DOMAIN"
EOF

echo "Waiting for the certificate..."
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://$DOMAIN/" || true)
  if [[ "$code" == "401" ]]; then
    echo "HTTPS is live: https://$DOMAIN  (password-protected)"
    echo "Kite redirect URL: https://$DOMAIN/auth/callback"
    exit 0
  fi
  sleep 5
done
echo "Certificate not ready yet. Check: ssh ... 'sudo journalctl -u caddy -n 50'  (are TCP 80/443 open in the Lightsail firewall?)"
exit 1
