#!/usr/bin/env bash
set -euo pipefail

kit_dir="$(cd "$(dirname "$0")" && pwd)"
model="$kit_dir/gemma-4-E2B-it.litertlm"
apk="$kit_dir/Invocta-debug.apk"
expected_sha256=ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42
device_dir='/sdcard/Android/data/com.invocta/files/Download/models'
device_model="$device_dir/gemma-4-E2B-it.litertlm"

[[ -f "$apk" && -f "$model" ]] || { echo "APK or model missing from $kit_dir" >&2; exit 1; }
[[ "$(shasum -a 256 "$model" | cut -d ' ' -f 1)" == "$expected_sha256" ]] || {
  echo "Local model checksum does not match the pinned artifact." >&2
  exit 1
}

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  serials="$(adb devices | awk '$2 == "device" { print $1 }')"
  [[ -n "$serials" && "$serials" != *$'\n'* ]] || {
    echo "Connect exactly one device, or set ANDROID_SERIAL to the intended device." >&2
    exit 1
  }
  export ANDROID_SERIAL="$serials"
fi
[[ "$(adb devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial && $2 == "device" { print $1 }')" == "$ANDROID_SERIAL" ]] || {
  echo "Selected device $ANDROID_SERIAL is not connected and ready." >&2
  exit 1
}

adb wait-for-device
for ((attempt = 0; attempt < 60; attempt++)); do
  [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] && break
  sleep 2
done
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
  echo "Android has not finished booting; try again after the device starts." >&2
  exit 1
}
adb install -r "$apk"
adb shell am force-stop com.invocta
adb shell mkdir -p "$device_dir"
installed_sha256="$(adb shell sha256sum "$device_model" 2>/dev/null | cut -d ' ' -f 1 | tr -d '\r' || true)"
if [[ "$installed_sha256" != "$expected_sha256" ]]; then
  adb push "$model" "$device_model.part"
  device_sha256="$(adb shell sha256sum "$device_model.part" | cut -d ' ' -f 1 | tr -d '\r')"
  [[ "$device_sha256" == "$expected_sha256" ]] || {
    echo "Device model checksum mismatch; the partial copy was not installed." >&2
    exit 1
  }
  adb shell mv "$device_model.part" "$device_model"
fi
adb shell rm -f "$device_dir/gemma-4-E2B-it.litertlm.download"
echo "Installed Invocta and Gemma. Open Settings and select Use Gemma."
