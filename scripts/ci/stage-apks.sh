#!/usr/bin/env bash
set -euo pipefail

readonly OUTPUT_DIR="ci-apks"
readonly TARGET_NAME="Keepriva-debug.apk"
readonly TEST_NAME="Keepriva-debug-androidTest.apk"

find_exactly_one() {
  local description="$1"
  shift

  local -a matches=()
  mapfile -t matches < <(find "$@" -type f -name '*.apk' -print | sort)

  if (( ${#matches[@]} != 1 )); then
    echo "ERROR: expected exactly one ${description}; found ${#matches[@]}." >&2
    if (( ${#matches[@]} > 0 )); then
      printf '  %s\n' "${matches[@]}" >&2
    fi
    exit 1
  fi

  printf '%s\n' "${matches[0]}"
}

target_apk="$(find_exactly_one \
  'debug target APK' \
  app/build/outputs/apk/debug)"

test_apk="$(find_exactly_one \
  'debug instrumentation APK' \
  app/build/outputs/apk/androidTest)"

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

cp "$target_apk" "$OUTPUT_DIR/$TARGET_NAME"
cp "$test_apk" "$OUTPUT_DIR/$TEST_NAME"

(
  cd "$OUTPUT_DIR"
  sha256sum "$TARGET_NAME" "$TEST_NAME" > SHA256SUMS
  sha256sum --check SHA256SUMS
)

test -s "$OUTPUT_DIR/$TARGET_NAME"
test -s "$OUTPUT_DIR/$TEST_NAME"
test -s "$OUTPUT_DIR/SHA256SUMS"

echo "Staged immutable Keepriva APK bundle:"
ls -lh "$OUTPUT_DIR/$TARGET_NAME" "$OUTPUT_DIR/$TEST_NAME" "$OUTPUT_DIR/SHA256SUMS"

