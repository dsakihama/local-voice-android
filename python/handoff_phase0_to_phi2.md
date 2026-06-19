# Handoff: Phase 0 → Phi-2 Conversion

**Date:** 2026-06-18  
**From:** Whisper Small ONNX conversion (complete)  
**To:** Phi-2 int4 ONNX conversion (next)

---

## What's Done

Whisper Small has been converted to int8 ONNX and validated. Output lives at:

```
voice-project/python/whisper_small_int8/
  encoder_model_int8.onnx   (93.6 MB)
  decoder_model_int8.onnx   (300.5 MB)
```

Both models load in ONNX Runtime with correct I/O shapes. Do not move or rename these files — the Android project will reference them from `assets/models/`.

---

## What's Next

Convert `microsoft/phi-2` to ONNX format with int4 quantization for use as the LLM cleanup stage in the inference pipeline.

**Target output:**
```
voice-project/python/phi2_int4/
  phi2_int4.onnx    (~1.5 GB)
```

---

## Key Learnings to Carry Forward

These were hard-won during the Whisper conversion — apply them directly to the Phi-2 script:

**1. Use `importlib.metadata` for version checks — not `module.__version__`**
```python
from importlib.metadata import version as pkg_version
v = pkg_version("optimum")
```

**2. Pin optimum to 1.23.3**
Optimum 2.x removed the ONNX export CLI. Do not upgrade.
```bash
pip3 install 'optimum[exporters]==1.23.3'
```

**3. Use `optimum-cli` via resolved path, not `sys.executable -m`**
`pip3` installs CLI tools to `~/Library/Python/3.9/bin/` which is not on PATH by default. Resolve it programmatically:
```python
from pathlib import Path
cli = Path.home() / "Library" / "Python" / "3.9" / "bin" / "optimum-cli"
```

**4. Limit quantization to MatMul + Gemm only**
Quantizing Conv layers produces `ConvInteger` ops that ONNX Runtime CPU does not support.
```python
quantize_dynamic(src, dst, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])
```

**5. Make the script idempotent**
Export takes a long time. If quantization fails, the script should skip the export on re-run and only redo the failed step. Check for existing output files before each step.

**6. Suppress the LibreSSL warning**
macOS system Python 3.9 uses LibreSSL, which triggers a urllib3 warning on every run. Suppress it at the top of the script:
```python
import warnings
warnings.filterwarnings("ignore", message=".*NotOpenSSLWarning.*")
warnings.filterwarnings("ignore", category=UserWarning, module="urllib3")
```

---

## Phi-2 Specific Considerations

- **Model size:** `microsoft/phi-2` is ~5.5 GB to download. Budget time accordingly.
- **int4 vs int8:** The spec calls for int4 (not int8) for Phi-2 to hit the ~1.5 GB target. ONNX Runtime's `quantize_dynamic` supports `QuantType.QUInt4` and `QuantType.QInt4` — use `QUInt4` as it has broader ORT support.
- **Task:** `text-generation` (not `automatic-speech-recognition`)
- **Output:** Phi-2 is a decoder-only model, so expect a single ONNX file (not encoder + decoder split like Whisper).
- **RAM during conversion:** Phi-2 is large. Conversion may require 8–16 GB of RAM on the host machine. Close other apps before running.
- **Validation input:** Unlike Whisper (audio features), Phi-2 takes `input_ids` (token IDs). The validation step should use a short dummy token sequence.

---

## Environment Reference

| Item | Value |
|------|-------|
| Python | 3.9 (macOS system, CommandLineTools) |
| pip3 install location | `~/Library/Python/3.9/` |
| optimum | 1.23.3 (pinned) |
| onnxruntime | 1.19.2 |
| torch | 2.8.0 |
| transformers | 4.57.6 |

---

## Files to Reference

| File | Purpose |
|------|---------|
| `convert_whisper_to_onnx.py` | Working template — copy the preflight, idempotency, and quantization patterns |
| `conversion_log.md` | Full error history from Whisper conversion — check before debugging |
| `whisper_small_int8/` | Completed Whisper output — do not modify |
