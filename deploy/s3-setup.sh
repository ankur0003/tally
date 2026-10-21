#!/usr/bin/env bash
# One-time S3 backup setup. Run locally with AWS credentials that can create buckets and IAM users (`aws login`).
#   - private bucket in ap-south-1: public access blocked, versioning on, SSE-S3 encryption
#   - IAM user tally-s3-sync whose only permission is that bucket
#   - its access key goes straight into /etc/tally/s3.env on the server (root, 600); it is never printed
#   - installs the AWS CLI and the tally-s3-sync timer (every 15 min, plus 15:45 after the day review)
#
#   export TALLY_HOST=ubuntu@<static-ip> TALLY_SSH_KEY=~/.ssh/LightsailDefaultKey-ap-south-1.pem
#   ./deploy/s3-setup.sh [bucket-name]
set -euo pipefail
cd "$(dirname "$0")/.."

: "${TALLY_HOST:?set TALLY_HOST=ubuntu@<static-ip>}"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ServerAliveInterval=15 -o ConnectTimeout=20)
[[ -n "${TALLY_SSH_KEY:-}" ]] && SSH_OPTS+=(-i "$TALLY_SSH_KEY")
REGION=ap-south-1
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
BUCKET=${1:-tally-data-$ACCOUNT}
USER_NAME=tally-s3-sync

if aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "Bucket $BUCKET exists"
else
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
  echo "Created bucket $BUCKET"
fi
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
# Old versions of the constantly rewritten state files would pile up; keep 30 days of them.
aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration \
  '{"Rules":[{"ID":"expire-old-versions","Status":"Enabled","Filter":{"Prefix":""},"NoncurrentVersionExpiration":{"NoncurrentDays":30}}]}'

aws iam get-user --user-name "$USER_NAME" >/dev/null 2>&1 || aws iam create-user --user-name "$USER_NAME" >/dev/null
aws iam put-user-policy --user-name "$USER_NAME" --policy-name tally-bucket-only --policy-document "$(cat <<EOF
{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Action":["s3:ListBucket"],"Resource":"arn:aws:s3:::$BUCKET"},
  {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":"arn:aws:s3:::$BUCKET/*"}
]}
EOF
)"

# Rotate: drop existing keys so only the one on the server works.
for k in $(aws iam list-access-keys --user-name "$USER_NAME" --query 'AccessKeyMetadata[].AccessKeyId' --output text); do
  aws iam delete-access-key --user-name "$USER_NAME" --access-key-id "$k"
done
read -r KEY_ID KEY_SECRET < <(aws iam create-access-key --user-name "$USER_NAME" --query 'AccessKey.[AccessKeyId,SecretAccessKey]' --output text)

printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SECRET_ACCESS_KEY=%s\nAWS_DEFAULT_REGION=%s\nTALLY_S3_BUCKET=%s\n' "$KEY_ID" "$KEY_SECRET" "$REGION" "$BUCKET" |
  ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'sudo install -m 600 -o root -g root /dev/stdin /etc/tally/s3.env'
unset KEY_SECRET

ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'sudo mkdir -p /opt/tally && cat > /tmp/tally-s3-sync.sh && sudo install -m 755 /tmp/tally-s3-sync.sh /opt/tally/s3-sync.sh' < deploy/s3-sync.sh
ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'cat > /tmp/tally-s3-sync.service' < deploy/tally-s3-sync.service
ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'cat > /tmp/tally-s3-sync.timer' < deploy/tally-s3-sync.timer
ssh "${SSH_OPTS[@]}" "$TALLY_HOST" 'set -e
  command -v aws >/dev/null || sudo snap install aws-cli --classic
  sudo install -m 644 /tmp/tally-s3-sync.service /tmp/tally-s3-sync.timer /etc/systemd/system/
  sudo systemctl daemon-reload
  sudo systemctl enable --now tally-s3-sync.timer
  sudo systemctl start tally-s3-sync.service
  sudo journalctl -u tally-s3-sync -n 5 --no-pager'
echo "S3 backup ready: s3://$BUCKET"
