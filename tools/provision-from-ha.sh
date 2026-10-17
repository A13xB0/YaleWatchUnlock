#!/usr/bin/env bash
# Copy the existing Yale BLE offline key + slot from Home Assistant to the watch.
#
#   HA config entry --ssh--> this pipe --adb shell stdin--> watch provider --> Keystore
#
# The credential is never printed, written to disk, or placed in any command
# line (host or watch): it only exists inside the pipe. The watch accepts it
# only while "Provision over ADB" is open on the watch, and only once.
#
# Requirements: SSH key access to the Advanced SSH & Web Terminal app (jq is
# included there), and adb connected to the watch via Wireless debugging
# (TLS-paired). Do not use legacy `adb tcpip`, which is unencrypted.
#
# Environment overrides:
#   HA_HOST (homeassistant.local)  HA_PORT (22222)  HA_USER (hassio)
#   YALE_ENTRY_ID  - required only if Home Assistant has several yalexs_ble entries
#   ADB            - adb binary (default: adb on PATH, else ~/Android/Sdk/platform-tools/adb)
#   ANDROID_SERIAL - pick the watch when several devices are attached
set -euo pipefail
set +x # never trace: a trace could echo pipeline state

HA_HOST=${HA_HOST:-homeassistant.local}
HA_PORT=${HA_PORT:-22222}
HA_USER=${HA_USER:-hassio}
ENTRY_ID=${YALE_ENTRY_ID:-}
STORAGE=/config/.storage/core.config_entries
PKG=uk.co.hectospark.yaleunlock
AUTHORITY=content://$PKG.provision

if [[ -z ${ADB:-} ]]; then
  if command -v adb >/dev/null; then ADB=adb; else ADB="$HOME/Android/Sdk/platform-tools/adb"; fi
fi

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
remote() { ssh -o BatchMode=yes -p "$HA_PORT" "$HA_USER@$HA_HOST" "$(printf '%q ' "$@")" </dev/null; }

# 1. The watch: reachable, app installed, provisioning armed.
"$ADB" get-state >/dev/null 2>&1 || die "no ADB device (set ANDROID_SERIAL if several are attached)"
"$ADB" shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG is not installed on the ADB device"
status=$("$ADB" shell content query --uri "$AUTHORITY/status" 2>&1) || die "cannot query the watch: $status"
[[ $status == *"armed=1"* ]] || die "not armed: on the watch open Front Door > Set up > Provision over ADB, then re-run"

# 2. Home Assistant: exactly one matching yalexs_ble entry (counts only, no secrets).
select='.data.entries[] | select(.domain == "yalexs_ble" and ($id == "" or .entry_id == $id))'
count=$(remote jq --arg id "$ENTRY_ID" "[$select] | length" "$STORAGE") || die "SSH to $HA_USER@$HA_HOST:$HA_PORT failed"
case $count in
  1) ;;
  0) die "no yalexs_ble entry found${ENTRY_ID:+ with id $ENTRY_ID}" ;;
  *) die "$count yalexs_ble entries; set YALE_ENTRY_ID (ids: $(remote jq -r '[.data.entries[] | select(.domain == "yalexs_ble") | .entry_id] | join(", ")' "$STORAGE"))" ;;
esac

# 3. Stream key, slot, local_name and address straight into the watch.
#    `adb shell` (v2) forwards stdin and waits for the command to exit; `adb exec-in`
#    returns at stdin EOF and can kill the slow-starting `content` tool before it runs.
remote jq -c --arg id "$ENTRY_ID" "$select | .data | {key, slot, local_name, address}" "$STORAGE" \
  | "$ADB" shell "content write --uri $AUTHORITY/credential" \
  || die "transfer failed (re-arm on the watch and retry)"

# 4. Confirm via the outcome flag only; nothing is read back.
for _ in 1 2 3 4 5 6 7 8 9 10; do
  status=$("$ADB" shell content query --uri "$AUTHORITY/status" 2>/dev/null || true)
  case $status in
    *"result=received"*) echo "Credential stored on the watch."; exit 0 ;;
    *"result=rejected"*) die "the watch rejected the payload; see the message on the watch" ;;
  esac
  sleep 0.5
done
die "the watch did not confirm; check the message on the watch"
