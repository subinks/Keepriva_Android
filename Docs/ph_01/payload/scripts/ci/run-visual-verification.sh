#!/usr/bin/env bash
set -euo pipefail

readonly TARGET_PACKAGE="com.example.privatevault.debug"
readonly ACTIVITY="com.example.privatevault.MainActivity"
readonly TARGET_APK="ci-apks/Keepriva-debug.apk"
readonly TEST_APK="ci-apks/Keepriva-debug-androidTest.apk"
readonly OUTPUT_DIR="ci-artifacts/visual"
readonly SCREENSHOT_DIR="$OUTPUT_DIR/screenshots"
readonly UI_DIR="$OUTPUT_DIR/ui"
readonly LOG_DIR="$OUTPUT_DIR/logs"
readonly UI_DUMP_ATTEMPTS=12
readonly UI_DUMP_RETRY_DELAY_SECONDS=1

mkdir -p "$SCREENSHOT_DIR" "$UI_DIR" "$LOG_DIR"

capture_failure_diagnostics() {
  local original_status="$1"
  set +e
  adb devices -l > "$LOG_DIR/adb-devices.txt" 2>&1
  adb shell dumpsys activity processes > "$LOG_DIR/activity-processes.txt" 2>&1
  adb shell dumpsys package "$TARGET_PACKAGE" > "$LOG_DIR/package-dump.txt" 2>&1
  adb exec-out screencap -p > "$SCREENSHOT_DIR/failure-screen.png" 2>/dev/null
  adb shell uiautomator dump /sdcard/keepriva-visual-failure.xml >/dev/null 2>&1
  adb pull /sdcard/keepriva-visual-failure.xml "$UI_DIR/failure-ui.xml" >/dev/null 2>&1
  adb logcat -d > "$LOG_DIR/logcat.txt" 2>&1
  set -e
  return "$original_status"
}

on_exit() {
  local status="$?"
  if (( status != 0 )); then
    capture_failure_diagnostics "$status" || true
  fi
  exit "$status"
}
trap on_exit EXIT

dump_ui() {
  local remote_file="$1"
  local local_file="$2"
  local expected_text="${3:-<hierarchy}"
  local description="${4:-UI hierarchy}"
  local attempt
  local dump_output

  for attempt in $(seq 1 "$UI_DUMP_ATTEMPTS"); do
    adb shell rm -f "$remote_file" >/dev/null 2>&1 || true
    rm -f "$local_file"

    dump_output="$(adb shell uiautomator dump "$remote_file" 2>&1)" || true
    if adb pull "$remote_file" "$local_file" >/dev/null 2>&1 \
       && [[ -s "$local_file" ]] \
       && grep -Fq '<hierarchy' "$local_file" \
       && grep -Fq "$expected_text" "$local_file"; then
      if (( attempt > 1 )); then
        echo "PASS: $description became available on UI dump attempt $attempt."
      fi
      return 0
    fi

    {
      echo "Attempt $attempt/$UI_DUMP_ATTEMPTS: $description was not ready."
      if [[ -n "$dump_output" ]]; then
        echo "$dump_output"
      fi
    } >> "$LOG_DIR/uiautomator-dump-retries.log"

    if (( attempt < UI_DUMP_ATTEMPTS )); then
      sleep "$UI_DUMP_RETRY_DELAY_SECONDS"
    fi
  done

  echo "ERROR: $description did not become available after $UI_DUMP_ATTEMPTS attempts." >&2
  return 1
}

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

if ! adb shell pm path "$TARGET_PACKAGE" | grep -q '^package:'; then
  echo "ERROR: target package $TARGET_PACKAGE is not installed." >&2
  exit 1
fi

adb shell pm clear "$TARGET_PACKAGE" >/dev/null
adb logcat -c || true

echo "=== Capture fresh setup screen ==="
adb shell am start -W -n "$TARGET_PACKAGE/$ACTIVITY"
sleep 3
dump_ui \
  /sdcard/keepriva-setup.xml \
  "$UI_DIR/01-fresh-install-ui.xml" \
  "Create Keepriva" \
  "fresh setup screen"
adb exec-out screencap -p > "$SCREENSHOT_DIR/01-fresh-install-setup.png"
grep -q "Create Keepriva" "$UI_DIR/01-fresh-install-ui.xml"
grep -q "Create encrypted vault" "$UI_DIR/01-fresh-install-ui.xml"

echo "=== Create deterministic visual-verification vault ==="
python3 - <<'PY'
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PASSWORD = "KeeprivaTest123"


def adb(*args, check=True):
    return subprocess.run(
        ["adb", *args],
        check=check,
        text=True,
        capture_output=True,
    )


def dump(attempts=12, delay_seconds=1):
    last_error = "UI hierarchy dump did not run"

    for attempt in range(1, attempts + 1):
        adb("shell", "rm", "-f", "/sdcard/window.xml", check=False)
        dump_result = adb(
            "shell",
            "uiautomator",
            "dump",
            "/sdcard/window.xml",
            check=False,
        )
        result = adb("shell", "cat", "/sdcard/window.xml", check=False)

        if dump_result.returncode == 0 and result.stdout.strip():
            try:
                root = ET.fromstring(result.stdout)
                if root.tag == "hierarchy":
                    if attempt > 1:
                        print(
                            "UI hierarchy became available on "
                            f"attempt {attempt}/{attempts}."
                        )
                    return root
                last_error = f"unexpected XML root: {root.tag}"
            except ET.ParseError as error:
                last_error = f"invalid UI hierarchy XML: {error}"
        else:
            last_error = (
                dump_result.stderr.strip()
                or dump_result.stdout.strip()
                or "UI hierarchy dump is empty"
            )

        if attempt < attempts:
            time.sleep(delay_seconds)

    raise RuntimeError(
        f"UI hierarchy unavailable after {attempts} attempts: {last_error}"
    )


def center(bounds):
    numbers = [int(value) for value in re.findall(r"\d+", bounds)]
    if len(numbers) != 4:
        raise RuntimeError(f"Unexpected bounds: {bounds}")
    x1, y1, x2, y2 = numbers
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_node(node):
    x, y = center(node.attrib["bounds"])
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.5)


root = dump()
edits = [
    node
    for node in root.iter("node")
    if node.attrib.get("class") == "android.widget.EditText"
]
if len(edits) < 2:
    raise RuntimeError(f"Expected two setup password fields; found {len(edits)}")

for node in edits[:2]:
    tap_node(node)
    adb("shell", "input", "text", PASSWORD)
    time.sleep(0.5)

root = dump()
button = next(
    (
        node
        for node in root.iter("node")
        if "Create encrypted vault" in node.attrib.get("text", "")
    ),
    None,
)
if button is None:
    raise RuntimeError("Create encrypted vault button was not found")

tap_node(button)
time.sleep(3)
PY

dump_ui \
  /sdcard/keepriva-home.xml \
  "$UI_DIR/02-vault-home-ui.xml" \
  "Search title, username, phone, website or notes" \
  "vault home screen"
adb exec-out screencap -p > "$SCREENSHOT_DIR/02-vault-home.png"
grep -q "Search title, username, phone, website or notes" "$UI_DIR/02-vault-home-ui.xml"

echo "=== Capture master-password unlock screen ==="
adb shell am force-stop "$TARGET_PACKAGE"
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell am start -W -n "$TARGET_PACKAGE/$ACTIVITY"
sleep 3
dump_ui \
  /sdcard/keepriva-unlock.xml \
  "$UI_DIR/03-real-unlock-ui.xml" \
  'content-desc="Master password"' \
  "master-password unlock screen"
adb exec-out screencap -p > "$SCREENSHOT_DIR/03-real-unlock-screen.png"
grep -q 'content-desc="Master password"' "$UI_DIR/03-real-unlock-ui.xml"
grep -q 'content-desc="Unlock with master password"' "$UI_DIR/03-real-unlock-ui.xml"

echo "=== Run optional non-blocking biometric diagnostic ==="
{
  echo "=== package features ==="
  adb shell pm list features || true
  echo
  echo "=== biometric service ==="
  adb shell dumpsys biometric || true
  echo
  echo "=== fingerprint service ==="
  adb shell dumpsys fingerprint || true
} > "$LOG_DIR/biometric-environment.txt" 2>&1

adb shell locksettings set-pin 0000 >> "$LOG_DIR/biometric-environment.txt" 2>&1 || true

mapfile -t runners < <(
  adb shell pm list instrumentation \
    | tr -d '\r' \
    | sed -n -E "s/^instrumentation:([^ ]+) \(target=${TARGET_PACKAGE}\)$/\1/p"
)

if (( ${#runners[@]} == 1 )); then
  set +e
  timeout 90s adb shell am instrument -w -r \
    -e class com.example.privatevault.KeeprivaBiometricCiTest \
    -e keeprivaBiometricCi 1 \
    "${runners[0]}" \
    > "$LOG_DIR/biometric-test.log" 2>&1 &
  biometric_pid=$!

  for _ in $(seq 1 20); do
    adb emu finger touch 1 >/dev/null 2>&1 || true
    sleep 1
  done

  wait "$biometric_pid"
  biometric_status=$?
  set -e
  echo "Biometric instrumentation exit code: $biometric_status" \
    >> "$LOG_DIR/biometric-environment.txt"
else
  echo "Expected one runner; found ${#runners[@]}." > "$LOG_DIR/biometric-test.log"
fi

adb shell am force-stop "$TARGET_PACKAGE" || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell am start -W -n "$TARGET_PACKAGE/$ACTIVITY" || true
sleep 3

dump_ui \
  /sdcard/keepriva-biometric.xml \
  "$UI_DIR/04-biometric-diagnostic-ui.xml" \
  '<hierarchy' \
  "optional biometric diagnostic screen" || true
adb exec-out screencap -p > "$SCREENSHOT_DIR/04-biometric-diagnostic.png" || true

if [[ -s "$UI_DIR/04-biometric-diagnostic-ui.xml" ]] \
   && grep -q 'content-desc="Unlock Keepriva with biometrics"' \
        "$UI_DIR/04-biometric-diagnostic-ui.xml"; then
  cp "$SCREENSHOT_DIR/04-biometric-diagnostic.png" \
    "$SCREENSHOT_DIR/04-biometric-unlock-screen.png"
  cp "$UI_DIR/04-biometric-diagnostic-ui.xml" \
    "$UI_DIR/04-biometric-unlock-ui.xml"
  echo "PASS: biometric-enabled Keepriva UI was available." \
    | tee -a "$LOG_DIR/biometric-environment.txt"
else
  echo "NOTICE: emulator has no usable enrolled biometric for Keepriva." \
    | tee -a "$LOG_DIR/biometric-environment.txt"
  echo "NOTICE: biometric diagnostic is non-blocking." \
    | tee -a "$LOG_DIR/biometric-environment.txt"
fi

adb shell dumpsys package "$TARGET_PACKAGE" > "$LOG_DIR/package-dump.txt" || true
adb logcat -d > "$LOG_DIR/logcat.txt" || true

test -s "$SCREENSHOT_DIR/01-fresh-install-setup.png"
test -s "$SCREENSHOT_DIR/02-vault-home.png"
test -s "$SCREENSHOT_DIR/03-real-unlock-screen.png"

echo "PASS: deterministic visual verification completed."
