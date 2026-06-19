"""
Whisper Small → ONNX (int8)
============================
Converts openai/whisper-small to ONNX format with int8 quantization,
ready for ONNX Runtime on Android.

Output: quantized model files in ./whisper_small_int8/
  - encoder_model_int8.onnx       (~40MB)
  - decoder_model_*_int8.onnx     (~110MB)
  Total: ~150MB — target size for Android asset bundling

Requirements (install once):
  pip3 install 'optimum[exporters]==1.23.3' transformers torch onnxruntime

  Note: optimum 2.x removed the ONNX export CLI — pin to 1.23.3.

Usage:
  python3 convert_whisper_to_onnx.py

Estimated time: 5-15 min depending on download speed + machine
"""

# Suppress harmless LibreSSL/urllib3 warning on macOS system Python
import warnings
warnings.filterwarnings("ignore", message=".*NotOpenSSLWarning.*")
warnings.filterwarnings("ignore", category=UserWarning, module="urllib3")

import os
import sys
import shutil
from pathlib import Path

# ── Paths ──────────────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
ONNX_DIR   = SCRIPT_DIR / "whisper_small_onnx"   # intermediate (full precision)
INT8_DIR   = SCRIPT_DIR / "whisper_small_int8"    # final output (quantized)
MODEL_ID   = "openai/whisper-small"


# ── Preflight checks ───────────────────────────────────────────────────────────
def preflight():
    errors = []

    # Python packages
    for pkg in ["transformers", "torch", "onnxruntime"]:
        try:
            __import__(pkg)
        except ImportError:
            errors.append(f"Missing package: {pkg}")

    # optimum version — must be 1.x (2.x removed ONNX export CLI)
    try:
        from importlib.metadata import version as pkg_version
        version = pkg_version("optimum")
        major = int(version.split(".")[0])
        if major >= 2:
            errors.append(
                f"optimum {version} installed — 2.x removed ONNX export.\n"
                f"  Fix: pip3 install 'optimum[exporters]==1.23.3'"
            )
        else:
            print(f"✓ optimum {version} (1.x confirmed)")
    except Exception:
        errors.append("Missing package: optimum\n  Fix: pip3 install 'optimum[exporters]==1.23.3'")

    # optimum-cli on PATH or in pip3's local bin
    cli = shutil.which("optimum-cli")
    if not cli:
        local_bin = (
            Path.home() / "Library" / "Python"
            / f"{sys.version_info.major}.{sys.version_info.minor}"
            / "bin" / "optimum-cli"
        )
        if local_bin.exists():
            cli = str(local_bin)
        else:
            errors.append(
                "optimum-cli not found.\n"
                "  Fix: pip3 install 'optimum[exporters]==1.23.3'"
            )

    if errors:
        print("\n⚠ Preflight failed:")
        for e in errors:
            print(f"  • {e}")
        sys.exit(1)

    print("✓ Preflight passed.")
    return cli


# ── Step 1: Export to ONNX (full precision) ────────────────────────────────────
def export_to_onnx(cli: str):
    import subprocess

    # Skip if already done
    existing = list(ONNX_DIR.glob("*.onnx"))
    if existing:
        print(f"\n[1/3] Export already complete ({len(existing)} files found). Skipping.")
        for f in existing:
            print(f"  {f.name}: {f.stat().st_size / 1024**2:.1f} MB")
        return

    print(f"\n[1/3] Exporting {MODEL_ID} to ONNX (full precision)...")
    print(f"      Output: {ONNX_DIR}")
    print("      Downloads ~500MB — may take several minutes.\n")

    ONNX_DIR.mkdir(parents=True, exist_ok=True)

    subprocess.run([
        cli, "export", "onnx",
        "--model", MODEL_ID,
        "--task", "automatic-speech-recognition",
        str(ONNX_DIR),
    ], check=True)

    print("\n✓ ONNX export complete.")
    for f in sorted(ONNX_DIR.glob("*.onnx")):
        print(f"  {f.name}: {f.stat().st_size / 1024**2:.1f} MB")


# ── Step 2: Quantize all exported models to int8 ──────────────────────────────
def quantize_to_int8():
    from onnxruntime.quantization import quantize_dynamic, QuantType

    onnx_files = sorted(ONNX_DIR.glob("*.onnx"))
    if not onnx_files:
        print("\n✗ No .onnx files found in", ONNX_DIR)
        print("  Export step may have failed. Delete whisper_small_onnx/ and retry.")
        sys.exit(1)

    INT8_DIR.mkdir(parents=True, exist_ok=True)

    print(f"\n[2/3] Quantizing {len(onnx_files)} model(s) to int8...")

    for src in onnx_files:
        dst = INT8_DIR / src.name.replace(".onnx", "_int8.onnx")

        # Skip if already quantized
        if dst.exists():
            print(f"  ↩ {dst.name} already exists. Skipping.")
            continue

        print(f"  Quantizing {src.name} → {dst.name}...")
        quantize_dynamic(
            str(src),
            str(dst),
            weight_type=QuantType.QInt8,
            op_types_to_quantize=["MatMul", "Gemm"],  # skip Conv — ConvInteger not supported in ORT CPU
        )
        print(f"  ✓ {dst.name}: {dst.stat().st_size / 1024**2:.1f} MB")

    print("\n✓ Quantization complete.")


# ── Step 3: Validate ───────────────────────────────────────────────────────────
def validate():
    import onnxruntime as ort

    int8_files = sorted(INT8_DIR.glob("*.onnx"))
    if not int8_files:
        print("\n✗ No int8 models found in", INT8_DIR)
        sys.exit(1)

    print(f"\n[3/3] Validating {len(int8_files)} int8 model(s)...")

    all_ok = True
    for f in int8_files:
        try:
            sess = ort.InferenceSession(str(f))
            inputs  = [i.name for i in sess.get_inputs()]
            outputs = [o.name for o in sess.get_outputs()]
            print(f"  ✓ {f.name}")
            print(f"    inputs:  {inputs}")
            print(f"    outputs: {outputs}")
        except Exception as e:
            print(f"  ✗ {f.name} failed: {e}")
            all_ok = False

    total_mb = sum(f.stat().st_size for f in int8_files) / 1024**2
    print(f"\n── Summary {'─'*45}")
    print(f"  Output: {INT8_DIR}")
    print(f"  Files:  {len(int8_files)}")
    print(f"  Total:  {total_mb:.1f} MB (target: ~150MB)")
    if all_ok:
        print("\n✓ All models validated. Ready for Android.")
        print("  Next: copy whisper_small_int8/ into your Android project's assets/models/")
    else:
        print("\n⚠ Some models failed validation — check errors above.")


# ── Main ───────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print("=" * 60)
    print("  Whisper Small → ONNX int8 Conversion")
    print("=" * 60)

    cli = preflight()
    export_to_onnx(cli)
    quantize_to_int8()
    validate()
