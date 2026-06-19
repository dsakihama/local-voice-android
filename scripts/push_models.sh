#!/usr/bin/env bash
# push_models.sh
# Push Whisper + Phi-2 ONNX model files to the Pixel 10 Pro XL via ADB.
#
# Run this once before first launch. Re-run if you re-convert the models.
#
# Usage:
#   cd voice-project
#   bash scripts/push_models.sh
#
# Prerequisites:
#   - adb installed and on PATH (comes with Android Studio platform-tools)
#   - Device connected via USB with USB debugging enabled
#   - App installed on device at least once (creates the files directory)

set -euo pipefail

PACKAGE="dev.dean.voice"
DEVICE_BASE="/sdcard/Android/data/${PACKAGE}/files/models"

# Absolute path to the model source directory (relative to script location)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODEL_SRC="${SCRIPT_DIR}/../python"

echo "=== Local Voice — Model Push Script ==="
echo ""

# ── Verify ADB is available ─────────────────────────────────────────────────
if ! command -v adb &>/dev/null; then
    echo "ERROR: adb not found on PATH."
    echo "Add Android Studio's platform-tools to PATH:"
    echo "  export PATH=\"\$PATH:\$HOME/Library/Android/sdk/platform-tools\""
    exit 1
fi

# ── Verify device connected ──────────────────────────────────────────────────
DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "ERROR: No device found. Connect your Pixel 10 Pro XL via USB."
    echo "Enable USB debugging: Settings → Developer Options → USB Debugging"
    exit 1
fi

echo "Device found. Pushing models..."
echo ""

# ── Create directories on device ────────────────────────────────────────────
adb shell mkdir -p "${DEVICE_BASE}/whisper_small_int8"
adb shell mkdir -p "${DEVICE_BASE}/phi2_int4"

# ── Push Whisper models ──────────────────────────────────────────────────────
echo "[ 1/4 ] Pushing Whisper encoder (~93.6 MB)..."
adb push "${MODEL_SRC}/whisper_small_int8/encoder_model_int8.onnx" \
    "${DEVICE_BASE}/whisper_small_int8/encoder_model_int8.onnx"

echo "[ 2/4 ] Pushing Whisper decoder (~300.5 MB)..."
adb push "${MODEL_SRC}/whisper_small_int8/decoder_model_int8.onnx" \
    "${DEVICE_BASE}/whisper_small_int8/decoder_model_int8.onnx"

# ── Push Phi-2 models ────────────────────────────────────────────────────────
echo "[ 3/4 ] Pushing Phi-2 ONNX skeleton..."
adb push "${MODEL_SRC}/phi2_int4/phi2_int4.onnx" \
    "${DEVICE_BASE}/phi2_int4/phi2_int4.onnx"

echo "[ 4/4 ] Pushing Phi-2 weights sidecar (~2.18 GB — this will take a while)..."
adb push "${MODEL_SRC}/phi2_int4/phi2_int4.onnx.data" \
    "${DEVICE_BASE}/phi2_int4/phi2_int4.onnx.data"

# ── Verify ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Push complete. Verifying files on device... ==="
adb shell ls -lh "${DEVICE_BASE}/whisper_small_int8/"
adb shell ls -lh "${DEVICE_BASE}/phi2_int4/"

echo ""
echo "Done. Launch the app — ModelManager will find models at:"
echo "  ${DEVICE_BASE}/"
