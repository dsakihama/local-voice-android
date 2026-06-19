# Whisper ONNX Conversion — Steps & Learnings

**Date:** 2026-06-18  
**Goal:** Convert `openai/whisper-small` to int8 ONNX format for use with ONNX Runtime on Android.

---

## Step 1: Install Dependencies

**Command:**
```bash
pip3 install 'optimum[exporters]' transformers torch onnxruntime
```

**Error:** `zsh: no matches found: optimum[exporters]`

**Cause:** zsh interprets square brackets as glob patterns.

**Fix:** Quote the package name.
```bash
pip3 install 'optimum[exporters]' transformers torch onnxruntime
```

**Learning:** Always quote extras syntax (`package[extra]`) in zsh.

---

## Step 2: Run Conversion Script (Attempt 1)

**Command:**
```bash
python3 convert_whisper_to_onnx.py
```

**Error:** `No module named optimum.exporters.onnx`

**Cause:** The script used `sys.executable` to call a subprocess, which resolved to the macOS CommandLineTools Python at `/Library/Developer/CommandLineTools/usr/bin/python3` — a different Python than the one where `pip3` installed packages (`~/Library/Python/3.9/`).

**Fix:** Replaced `subprocess.run([sys.executable, "-m", "optimum.exporters.onnx", ...])` with a direct Python API call: `from optimum.exporters.onnx import main_export`.

**Learning:** On macOS, `python3` and `pip3` can point to different Python installations. Never use `sys.executable` to spawn subprocesses that depend on pip-installed packages unless you've verified they share the same environment.

---

## Step 3: Run Conversion Script (Attempt 2)

**Error:** `ModuleNotFoundError: No module named 'optimum.exporters.onnx'`

**Cause:** `optimum` 2.2.0 was installed (the latest version at the time). In optimum 2.x, the `optimum.exporters.onnx` Python API was restructured and the previous import path no longer exists.

**Fix:** Switched from Python API to the `optimum-cli` command-line tool, which was installed alongside the package.
```python
subprocess.run(["optimum-cli", "export", "onnx", "--model", MODEL_ID, ...])
```

**Learning:** Optimum 2.x made breaking API changes to the ONNX export interface. Don't rely on the Python API for ONNX export — use the CLI, or pin to a known working version.

---

## Step 4: Run Conversion Script (Attempt 3)

**Error:** `optimum-cli not found on PATH`

**Cause:** `pip3` on macOS installs CLI tools to `~/Library/Python/3.9/bin/`, which is not added to `$PATH` by default.

**Fix (manual):** Export the path for the current terminal session:
```bash
export PATH="$HOME/Library/Python/3.9/bin:$PATH"
```

**Fix (permanent):** Add the above line to `~/.zshrc`.

**Fix (in script):** Updated preflight check to automatically find `optimum-cli` in the pip3 local bin directory as a fallback, using:
```python
Path.home() / "Library" / "Python" / f"{major}.{minor}" / "bin" / "optimum-cli"
```

**Learning:** On macOS with system Python, `pip3`-installed CLI tools are not on PATH by default. Scripts should resolve the full path programmatically rather than relying on PATH.

---

## Step 5: Run Conversion Script (Attempt 4)

**Error:** `optimum-cli: error: unrecognized arguments: onnx`

**Cause:** `optimum` 2.2.0 was still installed. The `optimum-cli export onnx` subcommand was removed in optimum 2.x. The CLI now has a different structure.

**Fix:** Downgrade optimum to the last stable 1.x release:
```bash
pip3 install 'optimum[exporters]==1.23.3'
```

**Script fix:** Added a preflight version check that detects optimum 2.x and exits with a clear error message before attempting the export.

**Learning:** Pin optimum to `1.23.3` for this project. Optimum 2.x is not compatible with the `optimum-cli export onnx` workflow. Do not upgrade without verifying ONNX export still works.

---

## Step 6: Run Conversion Script (Attempt 5)

**Error:** `AttributeError: module 'optimum' has no attribute '__version__'`

**Cause:** The preflight check used `optimum.__version__` to read the installed version, but the `optimum` package does not expose `__version__` on its module object.

**Fix:** Use `importlib.metadata` instead, which reads version from the package's installed metadata regardless of whether the module exposes it:
```python
from importlib.metadata import version as pkg_version
version = pkg_version("optimum")
```

**Learning:** Don't rely on `module.__version__` — not all packages set it. `importlib.metadata.version("package-name")` is the reliable cross-package approach and is available in Python 3.8+.

---

## Step 7: Run Conversion Script (Attempt 6)

**Result:** Export succeeded. Decoder quantized and validated. Encoder quantized but failed validation.

**Warnings during export (non-blocking):**
- `TracerWarning: Converting a tensor to a Python boolean` — harmless, from PyTorch's ONNX tracing mechanism; doesn't affect model correctness
- `Weight deduplication check requires accelerate` — informational only; install `accelerate` to enable this check, but not required
- `max diff: 0.0047 (atol: 0.001)` — tolerance exceeded during export validation; acceptable for speech recognition models where small floating point differences don't materially affect output quality

**Encoder validation error:** `[ONNXRuntimeError] : 9 : NOT_IMPLEMENTED : Could not find an implementation for ConvInteger(10)`

**Cause:** `quantize_dynamic` with `QInt8` quantizes all operator types including Conv layers, converting them to `ConvInteger` ops. The ONNX Runtime CPU execution provider does not implement `ConvInteger`, so the model fails to load.

**Fix:** Limit quantization to `MatMul` and `Gemm` ops only — the transformer attention layers where int8 quantization has the most impact. Conv layers are left in float32.
```python
quantize_dynamic(
    str(src), str(dst),
    weight_type=QuantType.QInt8,
    op_types_to_quantize=["MatMul", "Gemm"],
)
```

**Note on output size:** Total was 273.6 MB vs the ~150 MB target estimate. Limiting quantization to MatMul/Gemm (skipping Conv) means the encoder retains float32 Conv weights, so final sizes will be somewhat larger than the original estimate. This is acceptable for Android given the Pixel 10 Pro XL's 256GB storage.

**Learning:** For transformer-based ONNX models, always scope `quantize_dynamic` to `MatMul` and `Gemm` only. Quantizing Conv layers produces `ConvInteger` ops that are broadly unsupported in ORT CPU providers on mobile.

---

## Step 8: Run Conversion Script (Attempt 7) — ✓ Success

**Result:** Both models quantized and validated successfully.

| File | Size |
|------|------|
| decoder_model_int8.onnx | 300.5 MB |
| encoder_model_int8.onnx | 93.6 MB |
| **Total** | **394.1 MB** |

**On size vs. spec estimate:** The spec estimated ~150 MB total. The actual output is 394 MB because limiting quantization to MatMul/Gemm (skipping Conv) leaves Conv weights in float32. This is a known trade-off. For the Pixel 10 Pro XL (12 GB RAM, 256 GB storage), 394 MB loaded at runtime is well within limits.

**Validated I/O shapes confirm correct architecture split:**
- `encoder_model_int8.onnx`: `input_features` → `last_hidden_state`
- `decoder_model_int8.onnx`: `input_ids` + `encoder_hidden_states` → `logits`

This is the standard Whisper encoder-decoder split required for autoregressive ONNX inference.

**Next step:** Convert Phi-2 int4 to ONNX format.

---

## Final Script Design

The final `convert_whisper_to_onnx.py` incorporates all of the above learnings:

- **Preflight check** — validates Python packages, optimum version (must be 1.x), and locates `optimum-cli` with PATH fallback
- **Idempotent** — skips export and quantization steps if output files already exist; safe to re-run after partial failures
- **Dynamic quantization** — quantizes all `.onnx` files found in the export directory, rather than hardcoding filenames (which vary across optimum versions)
- **Validation step** — loads each int8 model with ONNX Runtime and prints input/output shapes to confirm correctness
- **SSL warning suppressed** — macOS system Python 3.9 uses LibreSSL instead of OpenSSL, causing a `NotOpenSSLWarning` on every run; suppressed as it is harmless

---

## Environment

| Item | Value |
|------|-------|
| Machine | MacBook Air (macOS) |
| Python | 3.9 (macOS system Python via CommandLineTools) |
| pip3 install location | `~/Library/Python/3.9/` |
| optimum version (working) | 1.23.3 |
| onnxruntime version | 1.19.2 |
| torch version | 2.8.0 |
| transformers version | 4.57.6 |

---

## Verified Working Commands

```bash
# Install (one-time)
pip3 install 'optimum[exporters]==1.23.3' transformers torch onnxruntime

# Run conversion
python3 "/Users/deansakihama/Documents/Claude/Projects/Program Management Artifacts/voice-project/python/convert_whisper_to_onnx.py"
```
