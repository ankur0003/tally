#!/usr/bin/env bash
# Provisions the Lightsail instance with a static IP and an SSH-only firewall.
# Run locally with the AWS CLI configured (`aws configure`). Safe to re-run: existing resources are reused.
#
#   ./deploy/lightsail-create.sh
#
# Env overrides: NAME (tally), REGION (ap-south-1), RAM_GB (2), SSH_CIDR (your current public IP /32).
set -euo pipefail

NAME="${NAME:-tally}"
REGION="${REGION:-ap-south-1}"
AZ="${AZ:-${REGION}a}"
RAM_GB="${RAM_GB:-2}"
IP_NAME="${NAME}-ip"
SSH_CIDR="${SSH_CIDR:-$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')/32}"
export AWS_DEFAULT_REGION="$REGION"

command -v aws >/dev/null || { echo "aws CLI not installed"; exit 1; }

if aws lightsail get-instance --instance-name "$NAME" >/dev/null 2>&1; then
  echo "Instance $NAME already exists, reusing it"
else
  # Look up IDs instead of hardcoding them; Lightsail revises bundle/blueprint IDs over time.
  BUNDLE=$(aws lightsail get-bundles --query \
    "bundles[?isActive && ramSizeInGb==\`${RAM_GB}\` && contains(supportedPlatforms, 'LINUX_UNIX') && !contains(bundleId, 'ipv6')] | sort_by(@, &price)[0].bundleId" --output text)
  BLUEPRINT=$(aws lightsail get-blueprints --query \
    "blueprints[?isActive && platform=='LINUX_UNIX' && starts_with(blueprintId, 'ubuntu_24')] | [0].blueprintId" --output text)
  [[ "$BUNDLE" != "None" && "$BLUEPRINT" != "None" ]] || { echo "Could not find bundle ($BUNDLE) or blueprint ($BLUEPRINT)"; exit 1; }

  echo "Creating $NAME in $AZ (bundle $BUNDLE, blueprint $BLUEPRINT)"
  aws lightsail create-instances --instance-names "$NAME" --availability-zone "$AZ" \
    --blueprint-id "$BLUEPRINT" --bundle-id "$BUNDLE" >/dev/null
  echo -n "Waiting for instance to run"
  until [[ "$(aws lightsail get-instance-state --instance-name "$NAME" --query 'state.name' --output text)" == "running" ]]; do
    echo -n "."; sleep 5
  done
  echo
fi

if ! aws lightsail get-static-ip --static-ip-name "$IP_NAME" >/dev/null 2>&1; then
  aws lightsail allocate-static-ip --static-ip-name "$IP_NAME" >/dev/null
fi
if [[ "$(aws lightsail get-static-ip --static-ip-name "$IP_NAME" --query 'staticIp.isAttached' --output text)" != "True" ]]; then
  aws lightsail attach-static-ip --static-ip-name "$IP_NAME" --instance-name "$NAME" >/dev/null
fi
STATIC_IP=$(aws lightsail get-static-ip --static-ip-name "$IP_NAME" --query 'staticIp.ipAddress' --output text)

# Replace the default firewall (22 + 80 open to the world) with SSH from your IP only. Port 8090 stays closed.
aws lightsail put-instance-public-ports --instance-name "$NAME" --port-infos \
  "fromPort=22,toPort=22,protocol=tcp,cidrs=${SSH_CIDR}" >/dev/null

KEY="$HOME/.ssh/lightsail-${REGION}.pem"
if [[ ! -f "$KEY" ]]; then
  aws lightsail download-default-key-pair --query 'privateKeyBase64' --output text > "$KEY"
  chmod 600 "$KEY"
fi

cat <<EOF

Instance:   $NAME ($REGION)
Static IP:  $STATIC_IP     <- register this in the Kite developer console (app settings -> IP whitelist)
SSH key:    $KEY
SSH open to: $SSH_CIDR (re-run with SSH_CIDR=... if your home IP changes)

Next:
  export TALLY_HOST=ubuntu@$STATIC_IP TALLY_SSH_KEY=$KEY
  ./deploy/deploy.sh --bootstrap
EOF
