# LiteRT-LM Migration (Phase 3)

**Status:** Landed — Gemma 4 E2B on CPU (GPU unusable on this device). E4B-CPU is the faithful-but-slower alternative.
**Date:** 2026-06-23

## Why

MediaPipe `tasks-genai:0.10.35` has no working Gemma 4 path on the Pixel 10 Pro XL:
- **GPU/OpenCL** → native SIGBUS in `LlmLiteRTOpenClExecutor::Create` (can't parse the Gemma 4 `.litertlm`).
- **CPU/XNNPack** → loads (~8s) but a single cleanup runs multi-minute (400%+ CPU, never returned).

MediaPipe LLM Inference is in maintenance mode; Gemma 4 `.litertlm` is built for the **LiteRT-LM** runtime, which drives the GPU/NPU. So we swap the runtime.

## Decision

Replace MediaPipe `tasks-genai` with **LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.13.1`** (latest as of 2026-06-04; pin it, don't use `latest.release`). Model unchanged: `gemma-4-E4B-it.litertlm` already on device.

## Verified API (from Config.kt @ main)

```kotlin
EngineConfig(
  modelPath: String,
  backend: Backend = Backend.CPU(),   // default is CPU — must set GPU explicitly
  maxNumTokens: Int? = null,          // total input+output budget = KV-cache size
  cacheDir: String? = null,           // context.cacheDir → faster 2nd load
)
ConversationConfig(
  systemInstruction: Contents? = null,
  samplerConfig: SamplerConfig? = null,
  automaticToolCalling: Boolean = true,
)
SamplerConfig(topK: Int, topP: Double, temperature: Double, seed: Int = 0)
Backend: CPU(threadCount: Int? = null) | GPU() | NPU(nativeLibraryDir: String = "")

Engine(config).use { e -> e.initialize(); e.createConversation(cfg).use { c -> c.sendMessage(text) } }
// sendMessage(String): String (sync) ; sendMessageAsync(String): Flow<String> (streaming)
```

## Implementation

- **Gradle:** drop `mediapipe-tasks-genai`, add `litertlm-android:0.13.1`. (APK size handled by the existing `arm64-v8a` ABI split.)
- **`MediaPipeLlmCleanup.kt` → `LlmCleanup.kt`** (renamed; class name was now misleading). Public surface unchanged (`warmup/isModelReady/clean/close/Result`) → `TranscribeViewModel` only needs the 4 references renamed.
  - Persistent `Engine` loaded once in `warmup()`; **fresh `Conversation` per `clean()` call** → stateless (no documented history reset).
  - `systemInstruction = Contents.of(prefix)` + `sendMessage(userContent)` maps onto the existing `CleanupPrompts.buildParts()` `(prefix, user)` pair — proper role separation, no concatenated blob.
  - **`maxNumTokens = 2048`** — LiteRT-LM has no per-call output cap; this bounds runaway generation (the CPU-hang failure mode) and avoids allocating the full 32K KV-cache.
  - **Greedy sampler** `SamplerConfig(topK=1, topP=1.0, temperature=0.0)` — the 1B's hallucination was partly sampling-driven; deterministic decoding directly attacks "making stuff up." (Quickstart defaults of temp 0.8 are the opposite of what cleanup wants.)
  - `PREFERRED_BACKEND = Backend.GPU()` — flip to `Backend.CPU()` to isolate GPU issues.

## Outcome (on-device, Pixel 10 Pro XL)

- **GPU is unusable for Gemma 4 here.** `Backend.GPU()` fails engine creation for **both** E2B and E4B generic `.litertlm` — `LiteRtLmJniException: Failed to create engine: INTERNAL` at `llm_litert_compiled_model_executor.cc:1951` / `litert_compiled_model.h:1472`. Fails fast (~700ms), handled (no crash). The bundled OpenCL accelerator (`libLiteRtClGlAccelerator.so`) can't compile these models on the Tensor G5 GPU.
- **Tensor G5 AOT build not runnable.** `gemma-4-E2B-it_Google_Tensor_G5.litertlm` needs a Google Tensor dispatch runtime that is **not in the public `litertlm-android` AAR** (only the OpenCL/GL accelerator is bundled; no `libLiteRtDispatch_*`). `Backend.NPU()` is Qualcomm-only.
- **CPU (XNNPack) works for both sizes.** Landed on **E2B-CPU** — acceptable latency + faithful (filler cleanup confirmed, no 1B-style fabrication). **E4B-CPU** is the more-faithful-but-slower alternative (one-line `MODEL_FILENAME` swap).
- **Revisit GPU/NPU** if a newer AAR adds a Google Tensor dispatch lib or fixes the OpenCL compile for Gemma 4.

## Test plan

1. Gradle sync resolves `litertlm-android:0.13.1`.
2. Build + run. Logcat tag `LlmCleanup`: expect `Loading…` → `ready.` → a **fast** `sendMessage` return on GPU.
3. If GPU loads: measure latency vs 1100 ms baseline; then faithfulness re-test (Notes prose vs checkboxes, word preservation) via the RAW/CLEANED panel.
4. If GPU crashes: set `PREFERRED_BACKEND = Backend.CPU()` (LiteRT-LM CPU may beat MediaPipe CPU); if still bad, file a LiteRT-LM issue.

## Rollback

Revert the Gradle + `LlmCleanup` commit; the Gemma 3 1B `.task` path is in git history. (Model files on device are independent of code.)

## Deferred

- **NPU / Tensor G5 build** — `Backend.NPU(nativeLibraryDir)` exists; missing-`.so` bug was `0.10.0`, Qualcomm-oriented. Revisit for `gemma-4-E2B-it_Google_Tensor_G5.litertlm` after GPU works.
- **Few-shot via `initialMessages`** — examples currently ride in `systemInstruction`; cleaner as proper turns later (Phase 3 in-context learning).
- **Streaming** — `sendMessageAsync` Flow for progressive UI; not needed for v1.
