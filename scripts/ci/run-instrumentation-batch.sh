#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
  echo "Usage: $0 <batch-id> <comma-separated-test-classes>" >&2
  exit 64
fi

readonly BATCH_ID="$1"
readonly TEST_CLASSES_RAW="$2"
# Folded YAML matrix values can insert spaces after commas. AndroidJUnitRunner
# expects a strict comma-separated class list, so normalize whitespace here.
readonly TEST_CLASSES="${TEST_CLASSES_RAW//[[:space:]]/}"
readonly TARGET_PACKAGE="com.example.privatevault.debug"
readonly TARGET_APK="ci-apks/Keepriva-debug.apk"
readonly TEST_APK="ci-apks/Keepriva-debug-androidTest.apk"
readonly OUTPUT_DIR="ci-artifacts/$BATCH_ID"
readonly RAW_OUTPUT="$OUTPUT_DIR/instrumentation-output.txt"
readonly SUMMARY="$OUTPUT_DIR/summary.txt"

case "$BATCH_ID" in
  auth-lifecycle-smoke) readonly EXPECTED_TESTS=32 ;;
  categories) readonly EXPECTED_TESTS=25 ;;
  item-core) readonly EXPECTED_TESTS=14 ;;
  data-transfer) readonly EXPECTED_TESTS=12 ;;
  security) readonly EXPECTED_TESTS=12 ;;
  serial-safety-net) readonly EXPECTED_TESTS=95 ;;
  *)
    echo "ERROR: unknown instrumentation batch '$BATCH_ID'." >&2
    exit 64
    ;;
esac

mkdir -p "$OUTPUT_DIR"

capture_diagnostics() {
  local original_status="$1"
  set +e
  adb devices -l > "$OUTPUT_DIR/adb-devices.txt" 2>&1
  adb shell getprop > "$OUTPUT_DIR/device-properties.txt" 2>&1
  adb shell dumpsys activity processes > "$OUTPUT_DIR/activity-processes.txt" 2>&1
  adb shell dumpsys package "$TARGET_PACKAGE" > "$OUTPUT_DIR/package-dump.txt" 2>&1
  adb exec-out screencap -p > "$OUTPUT_DIR/failure-screen.png" 2>/dev/null
  adb shell uiautomator dump /sdcard/keepriva-failure-ui.xml >/dev/null 2>&1
  adb pull /sdcard/keepriva-failure-ui.xml "$OUTPUT_DIR/failure-ui.xml" >/dev/null 2>&1
  adb logcat -d > "$OUTPUT_DIR/logcat.txt" 2>&1
  set -e
  return "$original_status"
}

on_exit() {
  local status="$?"
  if (( status != 0 )); then
    capture_diagnostics "$status" || true
  fi
  exit "$status"
}
trap on_exit EXIT

test -s "$TARGET_APK"
test -s "$TEST_APK"

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell svc power stayon true || true
adb shell settings put global stay_on_while_plugged_in 3 || true
adb shell settings put system screen_off_timeout 2147483647 || true
adb shell dumpsys deviceidle disable || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null || true

adb install -r "$TARGET_APK"
adb install -r -t "$TEST_APK"

mapfile -t runners < <(
  adb shell pm list instrumentation \
    | tr -d '\r' \
    | sed -n -E "s/^instrumentation:([^ ]+) \(target=${TARGET_PACKAGE}\)$/\1/p"
)

if (( ${#runners[@]} != 1 )); then
  echo "ERROR: expected exactly one instrumentation runner for $TARGET_PACKAGE; found ${#runners[@]}." >&2
  adb shell pm list instrumentation >&2 || true
  exit 1
fi
readonly RUNNER="${runners[0]}"

adb shell pm clear "$TARGET_PACKAGE" >/dev/null
adb logcat -c || true

START_EPOCH="$(date +%s)"
readonly START_EPOCH
set +e
adb shell am instrument -w -r \
  -e class "$TEST_CLASSES" \
  "$RUNNER" \
  | tr -d '\r' \
  | tee "$RAW_OUTPUT"
instrument_status=${PIPESTATUS[0]}
set -e
END_EPOCH="$(date +%s)"
readonly END_EPOCH
DURATION_SECONDS="$((END_EPOCH - START_EPOCH))"
readonly DURATION_SECONDS

if (( instrument_status != 0 )); then
  echo "ERROR: instrumentation command exited with $instrument_status." >&2
  exit "$instrument_status"
fi

if grep -Eq \
    'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=' \
    "$RAW_OUTPUT"; then
  echo "ERROR: failure marker found in instrumentation output." >&2
  exit 1
fi

ok_line="$(grep -Eo 'OK \([0-9]+ tests?\)' "$RAW_OUTPUT" | tail -n 1 || true)"
if [[ -z "$ok_line" ]]; then
  echo "ERROR: instrumentation output did not contain a final OK test count." >&2
  exit 1
fi

observed_tests="$(grep -Eo '[0-9]+' <<< "$ok_line" | head -n 1)"
if [[ "$observed_tests" != "$EXPECTED_TESTS" ]]; then
  echo "ERROR: batch $BATCH_ID ran $observed_tests tests; expected $EXPECTED_TESTS." >&2
  exit 1
fi

cat > "$SUMMARY" <<EOF
batch_id=$BATCH_ID
runner=$RUNNER
expected_tests=$EXPECTED_TESTS
observed_tests=$observed_tests
duration_seconds=$DURATION_SECONDS
status=passed
EOF

echo "PASS: $BATCH_ID completed $observed_tests tests in ${DURATION_SECONDS}s."
