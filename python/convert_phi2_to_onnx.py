"""
convert_phi2_to_onnx.py

Converts microsoft/phi-2 to ONNX format with int4 quantization.
Target output: voice-project/python/phi2_int4/phi2_int4.onnx (~1.5 GB)

Usage:
    python3 convert_phi2_to_onnx.py

Requirements:
    pip3 install 'optimum[exporters]==1.23.3' onnxruntime transformers torch

Notes:
    - Phi-2 is ~5.5 GB to download — budget time accordingly.
    - Conversion may require 8–16 GB RAM. Close other apps before running.
    - Script is idempotent: skips steps whose output files already exist.
"""

import warnings
warnings.filterwarnings("ignore", message=".*NotOpenSSLWarning.*")
warnings.filterwarnings("ignore", category=UserWarning, module="urllib3")

import subprocess
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
OUTPUT_DIR = Path("phi2_int4")
ONNX_FP32  = OUTPUT_DIR / "phi2_fp32.onnx"
ONNX_INT4  = OUTPUT_DIR / "phi2_int4.onnx"

MODEL_ID = "microsoft/phi-2"

# optimum-cli lives in ~/Library/Python/3.9/bin/ which is not on PATH by default
OPTIMUM_CLI = Path.home() / "Library" / "Python" / "3.9" / "bin" / "optimum-cli"


# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------
def preflight():
    """Verify required tools and versions before doing any work."""
    print("=== Preflight checks ===")

    # Python version
    major, minor = sys.version_info[:2]
    print(f"  Python {major}.{minor}")

    # optimum version via importlib.metadata (module.__version__ unreliable)
    from importlib.metadata import version as pkg_version
    optimum_ver = pkg_version("optimum")
    print(f"  optimum {optimum_ver}")
    if not optimum_ver.startswith("1.23"):
        print(f"  WARNING: expected optimum 1.23.3, got {optimum_ver}. "
              "optimum 2.x removed the ONNX export CLI.")

    # onnxruntime
    print(f"  onnxruntime {ort.__version__}")

    # optimum-cli binary
    if not OPTIMUM_CLI.exists():
        print(f"  ERROR: optimum-cli not found at {OPTIMUM_CLI}")
        print("  Run: pip3 install 'optimum[exporters]==1.23.3'")
        sys.exit(1)
    print(f"  optimum-cli found at {OPTIMUM_CLI}")

    print()


# ---------------------------------------------------------------------------
# Step 1: Export to FP32 ONNX via optimum-cli
# ---------------------------------------------------------------------------
def export_to_onnx():
    if ONNX_FP32.exists():
        print(f"=== Step 1: Export skipped (already exists: {ONNX_FP32}) ===\n")
        return

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"=== Step 1: Exporting {MODEL_ID} to FP32 ONNX ===")
    print("  This will download ~5.5 GB and may take 10–30 minutes.\n")

    cmd = [
        str(OPTIMUM_CLI), "export", "onnx",
        "--model", MODEL_ID,
        "--task", "text-generation",
        str(OUTPUT_DIR),
    ]
    print(f"  Running: {' '.join(cmd)}\n")

    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        print("  ERROR: ONNX export failed. Check output above.")
        sys.exit(1)

    # optimum-cli names the file 'model.onnx' — rename to our convention.
    # Note: large models use external data format; renaming only the .onnx file
    # is safe because the external data filename is embedded as a relative path
    # inside the .onnx file itself and is unaffected by the container rename.
    default_out = OUTPUT_DIR / "model.onnx"
    if default_out.exists() and not ONNX_FP32.exists():
        default_out.rename(ONNX_FP32)
        print(f"  Renamed model.onnx → {ONNX_FP32.name}")

    if not ONNX_FP32.exists():
        print(f"  ERROR: Expected output not found: {ONNX_FP32}")
        sys.exit(1)

    # Report total size including any external data sidecar files
    onnx_bytes = ONNX_FP32.stat().st_size
    data_files = list(OUTPUT_DIR.glob("*.onnx_data")) + list(OUTPUT_DIR.glob("*.weight"))
    data_bytes  = sum(f.stat().st_size for f in data_files)
    total_gb = (onnx_bytes + data_bytes) / 1e9
    print(f"  Export complete: {ONNX_FP32} (total on disk: {total_gb:.2f} GB)")
    if data_files:
        print(f"  External data: {[f.name for f in data_files]}")
    print()


# ---------------------------------------------------------------------------
# Step 2: Quantize FP32 → int4 via MatMul4BitsQuantizer
# ---------------------------------------------------------------------------
def quantize_to_int4():
    if ONNX_INT4.exists():
        print(f"=== Step 2: Quantization skipped (already exists: {ONNX_INT4}) ===\n")
        return

    print("=== Step 2: Quantizing FP32 → int4 (MatMul4BitsQuantizer) ===")
    print("  quantize_dynamic does not support direct int4 packing in ORT 1.x.")
    print("  MatMul4BitsQuantizer is the correct int4 path for transformer models.")
    print("  block_size=32, symmetric, accuracy_level=4 (best quality for int4).\n")

    # MatMul4BitsQuantizer accepts a model path string; it loads external data
    # automatically from the same directory as the .onnx file.
    quant = MatMul4BitsQuantizer(
        model=str(ONNX_FP32),
        block_size=32,
        is_symmetric=True,
        accuracy_level=4,
    )
    quant.process()

    # 2.18 GB exceeds protobuf's 2 GB single-file limit — use external data format.
    # Weights go to phi2_int4.onnx_data; the .onnx file is just the graph skeleton.
    quant.model.save_model_to_file(str(ONNX_INT4), use_external_data_format=True)

    if not ONNX_INT4.exists():
        print(f"  ERROR: Expected quantized output not found: {ONNX_INT4}")
        sys.exit(1)

    size_gb = ONNX_INT4.stat().st_size / 1e9
    print(f"  Quantization complete: {ONNX_INT4} ({size_gb:.2f} GB)\n")


# ---------------------------------------------------------------------------
# Step 3: Validate the int4 model loads and runs a forward pass
# ---------------------------------------------------------------------------
def validate():
    print("=== Step 3: Validation ===")

    sess = ort.InferenceSession(str(ONNX_INT4))

    # Print I/O info
    print("  Inputs:")
    for inp in sess.get_inputs():
        print(f"    {inp.name}: {inp.type} {inp.shape}")
    print("  Outputs:")
    for out in sess.get_outputs():
        print(f"    {out.name}: {out.type} {out.shape}")

    # Phi-2 takes input_ids (token IDs) — use a short dummy sequence
    # Shape: [batch_size, sequence_length]
    dummy_input_ids = np.array([[1, 2, 3, 4, 5]], dtype=np.int64)

    # Build feed dict — some exports also require attention_mask
    input_names = [inp.name for inp in sess.get_inputs()]
    feed = {}
    if "input_ids" in input_names:
        feed["input_ids"] = dummy_input_ids
    if "attention_mask" in input_names:
        feed["attention_mask"] = np.ones_like(dummy_input_ids, dtype=np.int64)
    if "position_ids" in input_names:
        seq_len = dummy_input_ids.shape[1]
        feed["position_ids"] = np.arange(seq_len, dtype=np.int64)[np.newaxis, :]

    print(f"\n  Running forward pass with dummy input_ids shape {dummy_input_ids.shape}...")
    outputs = sess.run(None, feed)
    print(f"  Output shape: {outputs[0].shape}")
    print("  Validation passed.\n")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    preflight()
    export_to_onnx()
    quantize_to_int4()
    validate()

    print("=== Done ===")
    print(f"  int4 model: {ONNX_INT4.resolve()}")
    print("  Copy phi2_int4.onnx into voice-project/python/phi2_int4/ before Android work.")
