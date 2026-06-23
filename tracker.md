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

**Where things stand (2026-06-23):** Flow pivot to transcript-first + native share sheet is complete, verified on-device (FAB, waveform, Obsidian/Gmail/Messages share all confirmed), and **committed** as `33b0ff4` on `main`. Full detail in the Done table below. Build is clean and installed on the Pixel 10 Pro XL. (Loose end: `design/share-text-requirements.md` is still untracked — commit or ignore as desired.)

**Next up (in priority order):**
1. **Decide the dormant accessibility path** — `UiState.SelectTarget` / `deliverTo()` / `buildSelectTarget()` / `launchApp()` in `TranscribeViewModel` and `SelectTargetSheet` in `MainScreen` are now dead code, kept only for a possible A/B vs the share sheet. Either wire up an A/B toggle or delete them. (App-usage frequency ranking + `AppUsageRecord` writes go dormant with it.)
2. **Decide clipboard behavior** — currently copies to clipboard on *every* share, which triggers the Android 13+ system "Copied" chip. Keep always-copy, or switch to copy-on-fallback only to suppress the chip.
3. **Remove `UiState.Result` comparison panel** — the RAW/CLEANED debug review screen is no longer on the main path; safe to delete (Phase 5 post-v1 TODO).
4. **Phase 7: Settings & Permissions** — next major build phase (mic/accessibility/clipboard permission flows, settings screen, data dashboard). See Phase 7 list.

**Model direction (decided 2026-06-23):** Gemma 3 1B did not meet expectations — it made things up (over-applied markdown, didn't faithfully preserve dictated words) and wasn't reliably coachable via examples. **Move cleanup to Gemma 4.** On-device Gemma 4 = **E2B / E4B** (effective ~2B / ~4B; there is no literal Gemma 4 "1B"). Target **E4B first** (safe, most faithful), then attempt to downsize to **E2B**; use the smallest variant that stays faithful + coachable. Strategy is example-driven: less free-form generation, more steering by pre-loaded few-shot "agent files" (this is the Phase 3 in-context-learning task). **NOTE: the on-device build is text-only — it does NOT replace ML Kit STT.** Full numbers in project memory.

**Specific models to download (Kaggle):**
- Kaggle model page: **`google/gemma-4`** (kaggle.com/models/google/gemma-4) — official Google release; the LiteRT-LM variations are mirrored 1:1 on Hugging Face under `litert-community`.
- **Primary → E4B:** variation `gemma-4-E4B-it-litert-lm` → file **`gemma-4-E4B-it.litertlm`** (HF mirror: `litert-community/gemma-4-E4B-it-litert-lm`). ~3.66 GB.
- **Downsize/fallback → E2B:** variation `gemma-4-E2B-it-litert-lm` → file **`gemma-4-E2B-it.litertlm`** (HF mirror: `litert-community/gemma-4-E2B-it-litert-lm`). ~1.5 GB.
- Want the **`-it`** (instruction-tuned) variants. Skip the `-web.task` files — those are for browser/WebGPU, not Android.

**⚠️ Format/runtime change — NOT a drop-in swap:** Gemma 4 ships as **`.litertlm`** (new LiteRT-LM format), not the **`.task`** format `MediaPipeLlmCleanup.kt` loads today via `tasks-genai:0.10.35`. MediaPipe LLM Inference is now in **maintenance mode**; Gemma 4 on-device is meant to run on the **LiteRT-LM** runtime (adds KV-cache mgmt, prompt templating, function calling on top of LiteRT). So Phase 3 likely means **migrating the runtime from MediaPipe `tasks-genai` → LiteRT-LM**, not just pushing a new model file. Confirm whether the installed `tasks-genai` can load `.litertlm` before assuming a clean swap; budget for the runtime migration if not.

**Deferred — do not pick up without the trigger:**
- **Notes markdown structuring** → was blocked on Gemma 3 4B; now folded into the Gemma 4 migration. Notes stays programmatic-only until Gemma 4 cleanup proves faithful (1B over-applied checkboxes).
- **AICore not-ready first-run UI** → deferred; self-managing STT model is `AVAILABLE` on this device, no crash path. See annotated note in Phase 6.

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

**Phase 3: LLM & Cleanup Refinement** ▶ Active focus — migrate to Gemma 4
- Text / Email / Notes now use fast programmatic cleanup (capitalize + strip fillers); LLM reserved for AI Prompt only
- Notes moved off the 1B LLM (2026-06-23) — model over-applied markdown (turned plain dictation into checkboxes). NOTES markdown prompt kept in `CleanupPrompts.kt`; rewire to LLM once Gemma 4 cleanup is faithful.
- **No longer blocked on Kaggle** — Gemma 4 E4B LiteRT (`litert-community/gemma-4-E4B-it-litert-lm`) already exists. See model-direction note above.
- [ ] **Migrate to Gemma 4 E4B-it** — download `gemma-4-E4B-it.litertlm` from Kaggle `google/gemma-4` (or HF `litert-community/gemma-4-E4B-it-litert-lm`). First confirm whether `tasks-genai:0.10.35` loads `.litertlm`; if not, migrate `MediaPipeLlmCleanup.kt` runtime from MediaPipe → **LiteRT-LM**. Set GPU backend + speculative decoding, measure latency/thermals on Pixel 10 Pro XL (Tensor G5).
- [ ] **Re-test faithfulness** across all intents (esp. Notes markdown + word preservation) — the original 1B failure mode
- [ ] **Attempt downsize E4B → E2B** once E4B proves the flow; keep whichever stays faithful, hold the other as fallback
- [ ] Prompt refinement loop — tune `CleanupPrompts.kt` across all intents on the new model
- [ ] Implement in-context learning ("agent files"): retrieve training pairs from Room, inject as few-shot examples into prompt — primary coachability lever
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
- [x] Auto-detect installed AI apps (Claude, ChatGPT, Perplexity) — `TargetAppRegistry.kt`
- [x] Auto-detect installed comms apps (Slack, WhatsApp, Gmail, SMS) — `TargetAppRegistry.kt`
- [x] Auto-detect PKB apps (Obsidian) — `TargetAppRegistry.kt`
- [x] Accessibility Service text injection (primary) — `VoiceAccessibilityService.injectText()`
- [x] Foreground package tracking — `VoiceAccessibilityService.foregroundPackage` StateFlow
- [x] Clipboard fallback — `deliverTo()` in `TranscribeViewModel`
- [x] `AppUsageRecord` written on every delivery (both inject and clipboard paths)
- [x] Frequency-ranked app menu state — `UiState.SelectTarget` with ranked + installed app map
- [x] Target app menu UI — bottom sheet (grouped by category, sorted by frequency)
- [x] Package visibility declared in manifest (`<queries>` block — Android 11+ requirement)
- [x] Category order respects intent (Text → Comms first, Notes → PKB first, etc.)
- [x] Injection confirmed working end-to-end; messaging apps (Messages, WhatsApp) use clipboard fallback as designed
- [ ] TODO (post-v1): Remove `UiState.Result` comparison panel once model quality is stable enough to skip review step (keep it through the Gemma 4 faithfulness re-test — it's the RAW/CLEANED diff view)

**Phase 6: UI**
- [x] Flow reordered to transcript-first: record → STT → review transcript → pick intent → cleanup → share
- [x] Delivery via native share sheet (`ShareSheetLauncher`), intent-shaped targets — replaces custom app menu on the main path (injection kept dormant)
- [x] FAB (draggable, always-on system overlay) — confirmed working on-device
- [x] Intent selection bottom sheet (AI / Text / Email / Notes) — now shown *after* capture
- [x] Listening state (waveform animation, red dot) — `ListeningWave`, confirmed working
- [x] Processing state (spinner + label) — `ProcessingSteps`
- [x] Target app menu — superseded by native share sheet (Obsidian/Gmail/Messages confirmed working)
- [x] ~~Toast confirmations~~ — removed per feedback (share sheet is the confirmation)
- [ ] AICore not-ready state (first-run model not yet downloaded — show loading indicator, not a crash) — **DEFERRED (2026-06-23)**. This is about the AICore-managed STT model (Gemini Nano), which self-downloads on demand and is **not** CLI/ADB-pushable (only the Gemma cleanup `.task` is). On this device AICore STT is already `AVAILABLE` (Phase 2.1), so the download path never fires. `checkStatus()` already handles AVAILABLE→record, DOWNLOADABLE/DOWNLOADING→download, UNAVAILABLE→clean error screen — no crash path either way. Only real exposure is the post-restart/AICore-reset edge case, parked in Phase 8. Revisit before any non-dev release.

**Phase 7: Settings & Permissions**
- [ ] Microphone permission request flow
- [ ] Accessibility Service permission request flow
- [ ] Clipboard permission (Android 13+)
- [ ] Settings screen (language, auto-stop timer, feature toggles)
- [ ] Data dashboard (storage used, export, delete history)
- [ ] AICore dependency note in app (Pixel 10 required for Advanced STT; Basic mode fallback if scope expands)

**Future Investigations (post-Phase 8)**
- [x] ~~Evaluate Gemma 3 4B int4~~ / ~~Gemma 4 4B availability~~ — superseded: Gemma 4 E4B is now the active Phase 3 plan (see above). Gemma 4 on-device = E2B/E4B only.
- [ ] If E4B latency/thermals are unacceptable on Tensor G5 even on GPU, fall back to E2B; if both fail the bar, reconsider keeping AI-Prompt cleanup programmatic too.

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
| 2026-06-23 | Flow pivot **verified on-device** — FAB, waveform, transcript-first capture, and native share sheet all working. Confirmed: Obsidian share, Gmail (text → body, cursor left for recipient), Messages. Post-share toast removed per feedback. Notes moved to programmatic cleanup (1B over-applied checkboxes). |
| 2026-06-23 | Flow pivot implemented — transcript-first capture, intent chosen *after* STT, delivery via native share sheet with intent-shaped targets (`ShareSheetLauncher`). Accessibility injection kept dormant. |
| 2026-06-22 | Phase 5 complete — accessibility injection + clipboard fallback + app detection + frequency-ranked intent-aware app menu all working on device. Messaging apps use clipboard fallback as designed. |
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
