#!/usr/bin/env bash
# Fetch the release APK onto the device with resume, retrying through the
# connection resets this network produces. Runs the loop on the device so a
# single adb call can drive the whole transfer.
set -u

URL="${1:?usage: device-fetch-apk.sh <url> [dest]}"
DEST="${2:-/data/local/tmp/dsh.apk}"
EXPECTED="${EXPECTED_BYTES:-0}"

size() { stat -c%s "$DEST" 2>/dev/null || echo 0; }

for attempt in $(seq 1 200); do
  have="$(size)"
  if [ "$EXPECTED" -gt 0 ] && [ "$have" -ge "$EXPECTED" ]; then
    echo "complete: $have bytes"
    exit 0
  fi
  echo "attempt $attempt: have $have bytes"
  # -C - resumes; a reset exits nonzero and the loop retries from where it stopped.
  curl -sSL -C - --retry 5 --retry-delay 2 --retry-all-errors \
       --connect-timeout 20 --max-time 300 -o "$DEST" "$URL" >/dev/null 2>&1 || true
  now="$(size)"
  if [ "$now" -le "$have" ]; then
    # No progress this round; back off briefly before trying again.
    sleep 3
  fi
done

echo "gave up after 200 attempts at $(size) bytes" >&2
exit 1
