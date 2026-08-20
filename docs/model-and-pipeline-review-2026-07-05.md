# Model & Pipeline Review — Local Voice App

**Date:** 2026-07-05 · **Device:** Pixel 10 Pro XL (Tensor G5) · **Evidence:** codebase @ `main`, tracker.md, project journey, litert-lm-migration.md, plus current Google AI Edge docs/releases

---

## Verdict: are you using the right local models?

**STT — ML Kit GenAI Speech Recognition (Gemini Nano via AICore): Yes, keep it.**
On this device it's strictly better than the archived Whisper path: streaming partials vs. a 1,752ms batch baseline, zero model assets, OS-managed updates, TPU-accelerated, fully offline. Nothing you could self-host on this phone beats it for dictation.

**Cleanup — Gemma 4 E2B `.litertlm` on LiteRT-LM 0.13.1, CPU: Yes — it's the right *pragmatic* choice, but you're one runtime unlock away from the model actually intended for your phone.**

- The size call is correct. Your own testing established the floor empirically: 270M looped, 1B fabricated, E2B passed the faithfulness re-test. Greedy sampling (`topK=1, temp=0`) is exactly right for faithful cleanup.
- The runtime call is correct. LiteRT-LM 0.13.1 is still the **latest stable release** (v0.14.0-alpha.0 is pre-release only, Jun 18). MediaPipe exit was right; it's in maintenance mode.
- The backend is the compromise, not the model. `gemma-4-E2B-it_Google_Tensor_G5.litertlm` — already downloaded in `python/` — is the build made for your exact SoC, and it's sitting unused only because the public AAR lacks the Google Tensor dispatch lib. See recommendations R1/R2: there are now two concrete paths to fix this that didn't exist (or weren't checked) on Jun 23.

---

## 🔴 Red flags (correctness / will bite soon)

**F1 — `stripPreamble()` can silently delete legitimate output.** `LlmCleanup.kt:186-192` drops every leading line that ends with `:`. A valid NOTES result like `Groceries:` followed by a list loses its heading line. Same risk for any cleaned text whose first line legitimately ends in a colon. Fix: only strip known preamble patterns (e.g. lines matching `^(Here|Output|Cleaned).*:$`), or drop the heuristic and tighten the prompt instead. Related smell: `response.replace("\\n", "\n")` at `LlmCleanup.kt:156` rewrites literal backslash-n sequences inside user content, not just formatting artifacts.

**F2 — Token-budget collision the day few-shot examples ship.** `maxNumTokens = 2048` is the *total* input+output budget (`LlmCleanup.kt:52`). The NOTES system prompt is already ~500 tokens; `buildExampleBlock()` allows 10 examples (`CleanupPrompts.kt:111`), which at realistic dictation lengths adds 1,000–2,000 more; plus the transcript, plus the generation. Your #1 roadmap item (agent files) will overflow this quietly — truncated context or clipped output. Before wiring examples: raise `maxNumTokens` for example-bearing calls, cap the example block by estimated tokens (not count), and prefer 2–3 *relevant* examples over 10 recent ones (also cuts CPU prefill time — see F4).

**F3 — First-cleanup stall race.** `isModelReady()` returns true when the *file* exists, even if the engine isn't loaded (`LlmCleanup.kt:79`). `warmupPromptModel()` fires from `MainScreen` `LaunchedEffect` asynchronously; if the user records immediately (especially via the FAB overlay), `runCleanup` passes the ready-check and `clean()` late-initializes the engine — ~8s of silent stall inside the "Cleaning" state, looking like a hang. Fix: expose an engine-loaded state, show "Loading model…" distinctly, and start warmup at service/app start rather than screen entry.

---

## 🟡 Yellow flags (performance / hygiene)

**F4 — Full system-prompt re-prefill on every call.** Fresh `Conversation` per `clean()` is right for isolation, but on CPU the ~500-token NOTES prompt is re-prefilled every single time, and examples will multiply that. Mitigations: per-intent prompt slimming (the TEXT/EMAIL/AI prompt is already short), fewer/smaller examples, and R1 below (AICore caches the prompt prefix across requests — your `buildParts()` comment already anticipated this).

**F5 — All intents route through the LLM.** `runCleanup` sends TEXT/EMAIL/AI_PROMPT through Gemma even though their system prompt asks only for punctuation + filler removal — work `programmaticClean()` (`TranscribeViewModel.kt:373`) does in ~0ms. Tracker (Phase 3 header) says Text/Email use programmatic cleanup; the code no longer does — docs and code have diverged. Consider routing short TEXT/EMAIL dictations (< ~200 chars) programmatically and reserving the LLM for NOTES/AI_PROMPT and long inputs. Saves seconds of latency, battery, and thermals per capture.

**F6 — CPU thread count never tuned.** `Backend.CPU()` uses the default thread count (`LlmCleanup.kt:57`); the API accepts `CPU(threadCount)`. Tensor G5's big/mid/little topology means the default is unlikely optimal — benchmark 4/5/6 threads on E2B; this is the cheapest real speedup available today.

**F7 — No streaming.** Your own migration doc verified `sendMessageAsync(): Flow<String>` exists; `clean()` uses blocking `sendMessage`. Streaming won't cut total latency but gives progressive display during "Cleaning", earlier perceived completion, and a cancel point for runaway generations (your CPU-hang failure mode).

**F8 — Engine lifetime tied to the ViewModel.** `LlmCleanup` lives in `TranscribeViewModel`; `onCleared()` closes the engine, so lifecycle churn (overlay service vs. activity) can trigger a full 2.6GB reload. Promote `LlmCleanup` to an app-scoped singleton on `VoiceApp` alongside the repository.

**F9 — Dead Whisper stack still ships.** `whisper_vocab.json` (967KB) sits in `assets/` and goes into every APK; `WhisperInference` (a throwing stub), `WhisperTokenizer`, `WhisperFeatureExtractor`, `ModelManager`, and `AudioRecorder` (~520 LOC) are unreachable from the live flow. Delete or move to `python/` archive. Also: `isMinifyEnabled = false` in release — turn R8 on once the dead code is gone.

**F10 — Clipboard always-copy.** `shareCleaned()` copies every result to the clipboard before the share sheet. Beyond the Android 13+ chip (already tracked), dictated text is exposed to clipboard-sync/other apps — at odds with the sovereignty goal. Copy-on-fallback only, or mark the clip `EXTRA_IS_SENSITIVE`.

**F11 — `audioLengthMs = 0` on every persisted transcription** (`TranscribeViewModel.kt:266`). Harmless now, but it degrades the future training-pairs/analytics data you're planning to select few-shot examples with. Capture session duration while it's cheap.

---

## Performance recommendations (ranked by expected payoff)

**R1 — Re-test the ML Kit Prompt API. Biggest potential win; zero refactor risk.**
The Jun 19 blocker (`generateContent()` never returning) was observed on one specific AICore/ASI build. Since then, Prompt API ships publicly and is documented as performing **best on the Pixel 10 series running Gemini nano-v3 — same architecture family as the Gemma model you're running**. If it now works: cleanup moves to the Tensor TPU (~940 tok/s prefill measured in your own Phase 1 research), ~6GB of `.litertlm` files freed, prompt-prefix caching handled by AICore (kills F4), and LiteRT-LM stays as the offline-guaranteed fallback you've already built. One afternoon to probe; the A/B harness is already in place.

**R2 — Apply for Google Tensor ML SDK experimental access.**
The public LiteRT NPU path still covers only Qualcomm and MediaTek; Google Tensor NPU access is gated behind the **Tensor ML SDK experimental-access program** (sign-up on the LiteRT docs). Google's Tensor SDK Beta explicitly supports Pixel 10 Pro XL. This is the missing dispatch lib that blocks your already-downloaded `gemma-4-E2B-it_Google_Tensor_G5.litertlm` — NPU inference, lower thermals, GPU left free. Cost: a form.

**R3 — Watch LiteRT-LM v0.14 for the GPU unlock, then re-benchmark.**
v0.14.0-alpha.0 landed Jun 18. Two GPU-only features are waiting on the other side of the OpenCL compile failure: **Gemma 4 multi-token prediction (>2x decode speed, added v0.11)** and speculative decoding (v0.10.1). Re-test `Backend.GPU()` on each release; keep the pinned-version discipline.

**R4 — Tune `Backend.CPU(threadCount)`** (F6). Cheapest measurable gain available today without any dependency change.

**R5 — Route trivial cleanups programmatically** (F5). Sub-second TEXT/EMAIL captures with zero model cost; realigns code with the tracker's stated design.

**R6 — Fix the token budget before agent files ship** (F2) and select few examples by relevance rather than injecting 10 — this is both a correctness fix and a prefill-latency optimization.

**R7 — Stream + warmup lifecycle** (F3, F7, F8): app-scoped engine, warmup at overlay-service start, `sendMessageAsync` with a visible generation state and cancel.

**R8 — Delete the Whisper remnants and enable R8/minify** (F9). ~1MB APK now, larger win once minification is on.

---

## Sources

- Codebase: `LlmCleanup.kt`, `CleanupPrompts.kt`, `TranscribeViewModel.kt`, `SttProbe.kt`, `build.gradle.kts`, `libs.versions.toml`; project docs: `tracker.md`, `Voice Project Journey`, `design/Requirements/litert-lm-migration.md`
- [LiteRT-LM releases](https://github.com/google-ai-edge/LiteRT-LM/releases) — 0.13.1 latest stable; v0.11 MTP; v0.10.1 speculative decoding
- [Run LLMs using LiteRT-LM (NPU)](https://ai.google.dev/edge/litert/next/litert_lm_npu) — NPU vendors limited to Qualcomm/MediaTek; Google Tensor via experimental SDK
- [Tensor ML SDK experimental access](https://ai.google.dev/edge/litert/next/tensor_ml_sdk) · [Google Tensor SDK Beta with LiteRT](https://developers.googleblog.com/google-tensor-sdk-beta-with-litert/) — Pixel 10 series support
- [ML Kit GenAI Prompt API](https://developers.google.com/ml-kit/genai/prompt/android) · [Prompt API announcement](https://android-developers.googleblog.com/2025/10/ml-kit-genai-prompt-api-alpha-release.html) — best on Pixel 10 / Gemini nano-v3
