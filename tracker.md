# Voice Project Tracker

## Tool Guide

| Tool | Role | Who acts |
|------|------|----------|
| **Cowork** | Generate files: Kotlin source, Gradle configs, Room schema, Compose layouts, project scaffolding | Claude generates, Dean drops into project |
| **Claude Code** | Ongoing development on live codebase — multi-file edits, refactoring, debugging, Gradle builds, ADB commands | Claude Code acts on Dean's machine |
| **Android Studio** | Run/deploy to Pixel 10 Pro XL, Compose Preview, Logcat, profiling | Dean operates |

**Phase → Tool mapping:**
- Phase 0 (Model Conversion) → Complete. Python/Terminal no longer needed going forward.
- Phase 2.1 (Model Pivot) → Cowork for prototype snippets and validation
- Phases 1–8 (Core build) → Cowork for initial generation → Claude Code for ongoing dev → Android Studio for testing

**Stack pivot (decided 2026-06-19):**
Replaced ONNX Runtime + Whisper Small int8 + Phi-2 int4 with ML Kit GenAI STT + MediaPipe LLM Inference:
- STT → ML Kit GenAI Speech Recognition API, Advanced mode (Alpha) — Pixel 10 only, streams from mic via AICore
- LLM cleanup → MediaPipe LLM Inference (`tasks-genai:0.10.35`) + Gemma 3 1B int4 (`gemma3-1B-it-int4.task`) — bypasses AICore, ~1100ms cleanup latency
- ML Kit Prompt API was blocked — `generateContent()` never returns on ASI build `B.25.playstore.pixel10.919165660`
- Runs fully offline; audio and prompts never leave the device
- Full pivot rationale: `design/pivot20260619.md`

---

## Actions / In Progress

---

## Completed Phases

**Phase 0: Model Conversion (Python)**
- [x] Convert Whisper Small int8 to ONNX format
- [x] Convert Phi-2 int4 to ONNX format
- [x] Validate both models run locally in ORT 1.19.2
- [x] ~~Push models to device via ADB~~ — superseded by pivot; no model push needed with ML Kit

**Phase 1: Android Setup**
- [x] Scaffold Android project (Kotlin, Gradle config, package structure)
- [x] Add core dependencies (ONNX Runtime, Room, Compose, DataStore)
- [x] ~~Model delivery via ADB push~~ — superseded by pivot; models live in AICore
- [ ] **Update Gradle deps** — remove ONNX Runtime, add ML Kit GenAI (handled in Phase 2.1)

**Phase 2: Audio & STT**
- [x] Implement audio capture (AudioRecord API, 16kHz PCM) — carries forward to new stack
- [x] Silence detection + auto-stop (2 sec threshold) — behavior with ML Kit STT to be validated in Phase 2.1
- [x] ~~Whisper inference pipeline via ONNX Runtime~~ — superseded by pivot; archived in `python/`
- [x] Transcription accuracy baseline established (1752ms on CPU; NNAPI tested at ~2500ms, reverted)

---

## To-Do

**Phase 2.1: Model Improvement Pivot** ✓ Complete
- [x] Verify AICore + Gemini Nano availability on Pixel 10 Pro XL
- [x] Prototype ML Kit GenAI Speech Recognition (Advanced mode) — mic → transcript validated
- [x] Validate silence detection behavior with ML Kit STT
- [x] ~~Prototype ML Kit GenAI Prompt API~~ — blocked; `generateContent()` never returns on this AICore build
- [x] Update Gradle deps: remove ONNX Runtime, add ML Kit GenAI Speech Recognition
- [x] Archive ONNX model files (kept in `python/` as fallback)

**Phase 2.2: MediaPipe LLM Cleanup** ✓ Complete
- [x] Replace ML Kit Prompt API with MediaPipe LLM Inference (`tasks-genai:0.10.35`)
- [x] Implement `MediaPipeLlmCleanup.kt` — wraps `LlmInference`, logs RAW/CLEANED/latency
- [x] Build intent-specific prompt templates (`CleanupPrompts.kt`)
- [x] **Model: Gemma 3 1B GGUF** (`gemma-3-1b-it-Q4_K_M.gguf`) — rejected; MediaPipe does not accept GGUF format ("not a valid Flatbuffer buffer")
- [x] **Model: Gemma 3 270M int8** (`gemma-3-270m-it-int8.task`) — rejected; repetition loops on simple inputs, too small for reliable instruction following
- [x] **Model: Gemma 3 1B int4** (`gemma3-1B-it-int4.task`) — selected; ~1100ms cleanup latency, no repetition, acceptable output quality
- [x] **Commit all phase 2.2 changes** (6 modified files + `MediaPipeLlmCleanup.kt`)

**Phase 3: LLM & Cleanup Refinement** ⏸ Deferred — blocked on model availability
- Text / Email / Notes now use fast programmatic cleanup (capitalize + strip fillers); LLM reserved for AI Prompt only
- Unblocked by: Gemma 3 4B LiteRT release on Kaggle (not yet available as of 2026-06-20)
- [ ] Prompt refinement loop — tune `CleanupPrompts.kt` across all intents once 4B model is available
- [ ] Implement in-context learning: retrieve training pairs from Room, inject as few-shot examples into prompt
- [ ] Implement graceful degradation if model file missing (surface clear error, do not silently fail)
- [ ] Evaluate CPU vs GPU backend latency (set `setPreferredBackend` in `LlmInferenceOptions`)
- [ ] In-app model download flow (replace ADB push with bundled or downloadable model)
- [ ] Migrate off deprecated `LlmInference` API — `tasks-genai:0.10.35` marks it deprecated; check newer MediaPipe release for replacement API before bumping version

**Phase 4: Storage** ✓ Complete
- [x] Room database setup (transcriptions, app_usage, training_pairs) — entities, DAOs, VoiceDatabase all scaffolded
- [x] DataStore settings repository — AppSettingsRepository wired (standard DataStore, not encrypted)
- [x] VoiceRepository created — wraps all 3 DAOs; exposed from VoiceApp
- [x] Transcription saved to DB after every completed session (both LLM and programmatic paths)
- [ ] App frequency ranking logic (recency-weighted, last 30 days) — deferred to Phase 5 where it's consumed

**Phase 5: App Detection & Text Injection**
- [ ] Auto-detect installed AI apps (Claude, ChatGPT, Perplexity)
- [ ] Auto-detect installed comms apps (Slack, WhatsApp, Gmail, SMS)
- [ ] Auto-detect PKB apps (Obsidian)
- [ ] Accessibility Service text injection (primary)
- [ ] Clipboard + notification fallback

**Phase 6: UI**
- [ ] FAB (draggable, always-on system overlay)
- [ ] Intent selection bottom sheet (AI / Text / Email / Notes)
- [ ] Listening state (waveform animation, red dot)
- [ ] Processing state (spinner + label)
- [ ] Target app menu (grouped by category, sorted by frequency)
- [ ] Toast confirmations
- [ ] AICore not-ready state (first-run model not yet downloaded — show loading indicator, not a crash)

**Phase 7: Settings & Permissions**
- [ ] Microphone permission request flow
- [ ] Accessibility Service permission request flow
- [ ] Clipboard permission (Android 13+)
- [ ] Settings screen (language, auto-stop timer, feature toggles)
- [ ] Data dashboard (storage used, export, delete history)
- [ ] AICore dependency note in app (Pixel 10 required for Advanced STT; Basic mode fallback if scope expands)

**Future Investigations (post-Phase 8)**
- [ ] Evaluate Gemma 3 4B int4 (`.task` format, Kaggle) if 1B quality is insufficient after prompt tuning — ~2.5 GB, expect 5–15s inference vs ~1100ms; try GPU variant first (Tensor G4 via OpenCL/Vulkan)

**Phase 8: Testing & Polish**
- [ ] End-to-end test on Pixel 10 Pro XL
- [ ] Test injection into Gmail, Slack, WhatsApp, Obsidian
- [ ] Test clipboard fallback
- [ ] Battery drain measurement (ML Kit / AICore vs. prior ONNX baseline)
- [ ] Thermal testing (sustained dictation — AICore offloads to Tensor TPU, expect lower thermal than CPU inference)
- [ ] Test AICore cold-start latency (first call after device restart)
- [ ] Test AICore recovery after reset / AICore update (known setup edge case)
- [ ] Error handling + graceful degradation (AICore unavailable, model not ready, feature status errors)

---

## Done
<!-- Most recent first -->
| Date | Item |
|------|------|
| 2026-06-20 | MediaPipe LLM Inference + Gemma 3 1B int4 validated on device — cleanup working across all intents, ~1100ms latency. Prompt tuning in progress. |
| 2026-06-20 | ML Kit Prompt API confirmed blocked on ASI build `B.25.playstore.pixel10.919165660` — pivoted to MediaPipe which bundles its own inference engine and bypasses AICore. |
| 2026-06-19 | ML Kit GenAI STT validated on device — AICore available, streaming partials confirmed, noticeably faster than Whisper CPU baseline. Silence detection handled natively by the recognizer (CompletedResponse fires automatically). |
| 2026-06-19 | Gradle pivot complete — removed ONNX Runtime, added ML Kit GenAI Speech Recognition; removed MANAGE_EXTERNAL_STORAGE + largeHeap from manifest. |
| 2026-06-19 | Created `audio/SttProbe.kt` — standalone ML Kit GenAI Speech Recognition probe (checkAvailability + transcribe Flow). Verify import paths against ML Kit release notes before first build. |
| 2026-06-19 | Verified AICore availability — bootloader locked (green), `com.google.android.aicore` present, Android 16. Gemini Nano feature state (AVAILABLE vs DOWNLOADABLE) to be confirmed at runtime in Phase 2.1 prototype. |
| 2026-06-19 | Removed ONNX model files from device (`/sdcard/Android/data/dev.dean.voice/files/models/`) — ~2.57 GB freed. Local copies archived in `python/` as fallback. |
| 2026-06-19 | Stack pivot decided — replaced ONNX Runtime + Whisper + Phi-2 with ML Kit GenAI APIs (Gemini Nano via AICore). See `design/pivot20260619.md`. |
| 2026-06-19 | Transcription accuracy baseline — "This is a transcription test" transcribed correctly at 1752ms on CPU (NNAPI tested, slower ~2500ms, reverted). Whisper ONNX pipeline superseded by pivot. |
| 2026-06-18 | Phase 2: Whisper inference pipeline — WhisperFeatureExtractor (mel spectrogram), WhisperTokenizer (BPE decode), WhisperInference (ORT encoder + greedy decoder). Archived in `python/`. |
| 2026-06-18 | Phase 2: AudioRecorder implemented — Flow-based 16kHz PCM capture, RMS silence detection (2 sec), 60 sec max cap. Carries forward to new stack. |
| 2026-06-18 | Phase 1 scaffold complete — `android/` project with Gradle, manifest, all Kotlin stubs, ADB push script. Gradle deps need update per Phase 2.1. |
| 2026-06-18 | Converted Whisper Small to int8 ONNX (encoder 93.6 MB, decoder 300.5 MB). Archived in `python/whisper_small_int8/`. |
| 2026-06-18 | Converted Phi-2 to int4 ONNX (phi2_int4.onnx + phi2_int4.onnx_data, ~2.18 GB). Archived in `python/phi2_int4/`. |
| 2026-06-18 | Validated both ONNX models load and run forward pass in ORT 1.19.2. |
| 2026-06-18 | Created project folder structure (`design/`, `android/`, `python/`). |
