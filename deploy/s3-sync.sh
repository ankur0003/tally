#!/usr/bin/env bash
# Copies tally's data dir to S3: trade journal, playbook, day digests, ideas, audit logs, trader state.
# Runs as root from tally-s3-sync.service with /etc/tally/s3.env. Never uploads the Kite session (a live token).
#
#   /opt/tally/s3-sync.sh            # data dir -> s3://$TALLY_S3_BUCKET/tally/
#   /opt/tally/s3-sync.sh restore    # s3 -> data dir (new server; stop tally first)
set -euo pipefail
: "${TALLY_S3_BUCKET:?TALLY_S3_BUCKET not set (see /etc/tally/s3.env)}"
DATA=${TALLY_DATA_DIR:-/var/lib/tally}
DEST="s3://$TALLY_S3_BUCKET/tally"
EXCLUDES=(--exclude "kite_session.json" --exclude "instruments/*" --exclude "backup-*" --exclude "*.tmp")

if [[ "${1:-}" == "restore" ]]; then
  if systemctl is-active --quiet tally; then echo "Stop tally first: sudo systemctl stop tally"; exit 1; fi
  aws s3 sync "$DEST" "$DATA" "${EXCLUDES[@]}" --only-show-errors
  chown -R tally:tally "$DATA"
  echo "Restored $DEST -> $DATA"
  exit 0
fi

# No --delete: a file removed locally (e.g. a reset paper account) stays in S3 as history.
aws s3 sync "$DATA" "$DEST" "${EXCLUDES[@]}" --only-show-errors
# Dated daily snapshot of what the model learned, so the playbook's evolution can be compared over time.
DAY=$(TZ=Asia/Kolkata date +%F)
for f in ai_playbook.json ai_days.json ai_days_live.json journal.json journal_live.json; do
  [[ -f "$DATA/$f" ]] && aws s3 cp "$DATA/$f" "s3://$TALLY_S3_BUCKET/daily/$DAY/$f" --only-show-errors
done
echo "Synced $DATA -> $DEST"
