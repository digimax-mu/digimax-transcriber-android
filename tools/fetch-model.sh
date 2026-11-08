#!/usr/bin/env bash
# Downloads the Vosk small English model into app/src/main/assets/model.
# Run this once before building — the model (~40 MB) is not committed to git.
set -euo pipefail

MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
HERE="$(cd "$(dirname "$0")" && pwd)"
DST="$HERE/../app/src/main/assets/model"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading Vosk model…"
curl -fL --retry 4 "$MODEL_URL" -o "$TMP/model.zip"
unzip -q "$TMP/model.zip" -d "$TMP/x"
rm -rf "$DST"; mkdir -p "$DST"
cp -R "$TMP/x"/*/* "$DST"/
echo "Model placed in app/src/main/assets/model"
