#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v makensis >/dev/null || { echo 'Install NSIS (makensis).'; exit 1; }
mkdir -p target/bundle ../artifacts
archive=target/bundle/VBCABLE_Driver_Pack45.zip
expected=b950e39f01af1d04ea623c8f6d8eb9b6ea5c477c637295fabf20631c85116bfb
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 2 https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip -o "$archive"
fi
printf '%s  %s\n' "$expected" "$archive" | shasum -a 256 -c -
mkdir -p target/bundle/vbcable
unzip -qo "$archive" -d target/bundle/vbcable
cargo build --locked --release --target x86_64-pc-windows-gnu
python3 scripts/collect-notices.py
makensis -V2 installer/setup.nsi
