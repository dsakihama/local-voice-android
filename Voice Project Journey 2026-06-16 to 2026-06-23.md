# Local Voice App — Project Journey

**Span:** 2026-06-16 → 2026-06-23
**Platform:** Pixel 10 Pro XL (Android-first; macOS to follow)
**Owner:** Dean Sakihama

---

## Where it started

The vision was a personal, sovereign voice-to-text tool: tap a FAB, pick an intent (AI / Text / Email / Notes), dictate, let the app clean up the transcript based on that intent, then drop the polished text into a target app. Everything local, nothing leaving the device.

The strategy was Android-first — it's the always-with-you device — with macOS to follow once the experience was validated. The two workflows that mattered most were Obsidian capture and Claude prompts. The first technical bet was a fully self-hosted inference stack: **Whisper Small int8 for speech, Phi-2 int4 for cleanup, both on ONNX Runtime.**

## Phase 0–2: building the original stack (Jun 18)

Both models were converted to ONNX and validated in ORT 1.19.2 (Whisper ~394 MB, Phi-2 ~2.18 GB). The Android project was scaffolded, audio capture built, and a working Whisper inference pipeline stood up. Baseline transcription landed at ~1752ms on CPU — and notably, NNAPI was *slower* (~2500ms) and got reverted. First hint of a recurring theme: the accelerators kept losing to plain CPU on this hardware.

## Pivot #1 — drop ONNX for ML Kit / Gemini Nano (Jun 19)

Two problems killed the self-hosted stack:

- **Asset size.** 2.57 GB of models couldn't fit Android's 100 MB APK limit, forcing a clunky first-run download.
- **Performance ceiling.** Phi-2 on NNAPI would've been 2–8 tokens/sec, versus ~940 tok/sec prefill for Gemini Nano via AICore on the Tensor TPU.

The swap was to **ML Kit GenAI APIs** (STT + Prompt) running Gemini Nano through AICore — zero model assets, OS-managed. The bootloader was verified locked (AICore's hard requirement), and ML Kit STT validated cleanly on-device, streaming partials and beating the Whisper baseline. The Phi-2/Whisper work wasn't wasted — it validated the toolchain — but was archived as fallback.

## Pivot #2 — ML Kit Prompt API blocked, switch to MediaPipe (Jun 20)

The STT half worked; the cleanup half didn't. `generateContent()` never returned on the device's AICore build. Rather than wait on Google, cleanup moved to **MediaPipe LLM Inference**, which bundles its own engine and bypasses AICore. Model selection went the hard way:

- GGUF — rejected (MediaPipe won't load it)
- Gemma 3 270M int8 — rejected (repetition loops, too small to follow instructions)
- **Gemma 3 1B int4 — selected** (~1100ms, no loops, acceptable quality)

## The flow rethink (Jun 22–23)

Phase 5 delivered accessibility-service injection, clipboard fallback, app detection, and a frequency-ranked app menu — the whole "land text in the right app" machinery. The UX then pivoted to **transcript-first capture with the native Android share sheet** instead of custom injection:

`record → STT → review transcript → pick intent → cleanup → share`

The share-sheet research confirmed why this is the right call: you can't inject into a conversation you don't own, and receivers decide where text lands. Obsidian gets a deterministic `obsidian://new` path. Accessibility injection is now dormant code, kept only for a possible A/B. Verified on-device: FAB, waveform, Obsidian, Gmail, Messages all working.

## Pivot #3 — Gemma 3 1B not good enough, go to Gemma 4 on LiteRT-LM (Jun 23)

Gemma 3 1B failed the real test: it *made things up*, over-applied markdown (turning plain dictation into checkboxes), and wasn't reliably coachable by example — which mattered because example-driven steering is the whole point of the design.

The decision was to move to **Gemma 4 (E2B/E4B)**. But Gemma 4 ships as `.litertlm`, which MediaPipe couldn't run here at all: GPU/OpenCL crashed with a native SIGBUS, and CPU loaded but hung for minutes on a single cleanup. Since MediaPipe is in maintenance mode, the runtime migrated to **LiteRT-LM 0.13.1**.

## Where it landed

The accelerator story repeated one last time. On LiteRT-LM, `Backend.GPU()` fails engine creation for both E2B and E4B (the bundled OpenCL accelerator can't compile Gemma 4 on the Tensor G5), the Tensor-G5-specific AOT build isn't runnable (needs a dispatch lib absent from the public AAR), and NPU is Qualcomm-only. So CPU won again — but it works.

Current state:

- **STT:** ML Kit GenAI Speech Recognition (Gemini Nano via AICore), streaming, offline
- **Cleanup:** Gemma 4 **E2B on LiteRT-LM CPU**, greedy sampling, `maxNumTokens=2048` — faithfulness re-test passed (cleans fillers without fabricating, keeps notes as prose), with a Settings toggle to swap E2B↔E4B live for A/B
- **Delivery:** native share sheet, transcript-first flow, confirmed on-device
- **Storage:** Room (transcriptions, app_usage, training_pairs) wired

## The throughlines

1. **On this hardware, CPU keeps beating the accelerators.** NNAPI, OpenCL GPU, and the Tensor TPU were each tried and each fell short — fallback penalties, compile failures, or missing runtime libs.
2. **Maintenance-mode dependencies are traps.** MediaPipe's deprecation forced the LiteRT-LM migration; the project is now aligned with Google's actively-developed runtime.
3. **Smaller models fabricate.** The 270M looped and the 1B made things up; E2B is the proven floor for faithful, coachable cleanup.

## What's next

The big remaining payoff is in-context-learning "agent files" — feeding curated or captured few-shot examples into cleanup. It's now unblocked, with an A/B harness in place. That's the coachability feature that motivated the entire Gemma 4 move.
