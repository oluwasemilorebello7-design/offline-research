#!/usr/bin/env bash
# Copy a model (.gguf) and/or knowledge base (.sqlite) to the phone over adb.
# Usage: scripts/push_assets.sh Qwen3-4B-Instruct-2507-Q4_K_M.gguf data/knowledge.sqlite
# Tip: install + open the app once first so Android creates its data folder.
set -euo pipefail
PKG=org.offlineresearch.app
DEST=/sdcard/Android/data/$PKG/files
adb shell mkdir -p "$DEST/models" "$DEST/kb"
for f in "$@"; do
  case "$f" in
    *.gguf)   echo "-> model $f";  adb push "$f" "$DEST/models/" ;;
    *.sqlite) echo "-> kb    $f";  adb push "$f" "$DEST/kb/knowledge.sqlite" ;;
    *) echo "skip $f (expected .gguf or .sqlite)" ;;
  esac
done
echo "Done. Open the app (airplane mode is fine) and tap reload."
