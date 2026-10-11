#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
kit_dir="$repo_dir/dev-kit"
model="$kit_dir/gemma-4-E2B-it.litertlm"
partial="$model.part"
expected_size=2583085056
expected_sha256=ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42
model_url='https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm?download=true'

verify_model() {
  [[ -f "$1" ]] || return 1
  [[ "$(wc -c < "$1" | tr -d ' ')" == "$expected_size" ]] || return 1
  [[ "$(shasum -a 256 "$1" | cut -d ' ' -f 1)" == "$expected_sha256" ]]
}

mkdir -p "$kit_dir"
if ! verify_model "$model"; then
  if [[ -f "$model" ]]; then
    echo "Existing model has the wrong size or checksum: $model" >&2
    exit 1
  fi
  echo "Downloading the pinned Gemma 4 E2B model (2.58 GB)..."
  curl --fail --location --continue-at - --retry 3 --retry-all-errors --output "$partial" "$model_url"
  if ! verify_model "$partial"; then
    echo "Download is incomplete or failed checksum verification. Rerun this script to resume: $partial" >&2
    exit 1
  fi
  mv "$partial" "$model"
fi

(cd "$repo_dir" && ./gradlew :app:assembleDebug)
cp "$repo_dir/app/build/outputs/apk/debug/app-debug.apk" "$kit_dir/Invocta-debug.apk"
cp "$repo_dir/scripts/install-dev-kit.sh" "$kit_dir/install.sh"
cp "$repo_dir/scripts/dev-kit-README.md" "$kit_dir/README.md"
cp "$repo_dir/scripts/model-LICENSE-APACHE-2.0.txt" "$kit_dir/MODEL-LICENSE-APACHE-2.0.txt"
chmod +x "$kit_dir/install.sh"
echo "Development kit ready: $kit_dir"
