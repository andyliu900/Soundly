#!/usr/bin/env bash
set -euo pipefail

# Requires the SDK androidTest APK to be installed on an isolated device.
# This intentionally force-stops only com.ideacode.soundly_sdk.test.
if [[ $# != 1 || -z "$1" ]]; then
  echo "Usage: $0 <adb-device-serial>" >&2
  exit 2
fi
serial="$1"
test_package=com.ideacode.soundly_sdk.test
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"
test_class=com.ideacode.soundly_sdk.importer.ImportRestartInstrumentedTest
marker="/sdcard/Android/data/$test_package/files/i6-copy-active"
log_file="$(mktemp)"
trap 'rm -f "$log_file"' EXIT
adb -s "$serial" shell rm -f "$marker"
adb -s "$serial" shell am instrument -w -e i6_force_stop true -e class "$test_class#beginRealCopyUntilForceStopped" "$runner" >"$log_file" 2>&1 &
runner_pid=$!
ready=false
for ((attempt=0; attempt<60; attempt++)); do
  if adb -s "$serial" shell test -f "$marker"; then ready=true; break; fi
  if ! kill -0 "$runner_pid" 2>/dev/null; then break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  if kill -0 "$runner_pid" 2>/dev/null; then
    adb -s "$serial" shell am force-stop "$test_package"
    wait "$runner_pid" || true
  fi
  cat "$log_file" >&2
  echo "Copy did not reach the checkpoint; recovery was not verified." >&2
  exit 1
fi
adb -s "$serial" shell am force-stop "$test_package"
wait "$runner_pid" || true
adb -s "$serial" shell am instrument -w -e i6_force_stop true -e class "$test_class#recoverAfterRealCopyForceStop" "$runner" | tee "$log_file"
if ! grep -q 'OK (1 test)' "$log_file"; then exit 1; fi
