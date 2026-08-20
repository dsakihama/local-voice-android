# Claude Code Prompt Pack — Model & Pipeline Review Follow-ups

Companion to `docs/model-and-pipeline-review-2026-07-05.md`. One prompt per Claude Code session, in order. Commit between prompts. **Decision gate after Prompt 1:** if the Prompt API now works, Prompts 4–6 still apply (LiteRT-LM stays as the offline fallback), but tell Claude Code which engine is primary before running them.

Not prompts: R2 (Tensor ML SDK experimental-access form — you fill it in) and R3 (watch LiteRT-LM releases for the GPU fix — recurring check).

---

## Verified 2026-07-10 — still accurate, ready to run

Re-checked against the live repo (git log, `LlmCleanup.kt`, `TranscribeViewModel.kt`, `CleanupPrompts.kt`, `app/build.gradle.kts`) before handing this back to Claude Code. Findings:

- **No prompts have been run yet.** Last commit is `3ab3c80` (Jun 29), which predates this pack (Jul 5). Zero drift since — every F1–F11 finding in the review still reproduces exactly as described (line numbers included):
  - F1: `stripPreamble()` still strips any line ending in `:` (`LlmCleanup.kt:186-192`); `response.replace("\\n","\n")` unchanged (`:156`).
  - F2/F3/F8: `MAX_NUM_TOKENS = 2048` unchanged; `buildExampleBlock()` still `take(10)` with no token cap (`CleanupPrompts.kt:109-114`); `LlmCleanup` is still constructed inside `TranscribeViewModel` (`private val cleanup = LlmCleanup(app)`), not an app-scoped singleton on `VoiceApp` — `onCleared()` still calls `cleanup.close()`.
  - F5: `runCleanup` still routes **all** intents through the LLM — the comment even flags the tracker/code mismatch ("Phase 3: all intents now route through Gemma 4 E4B").
  - F9: `model/WhisperInference.kt`, `WhisperTokenizer.kt`, `ModelManager.kt`, `audio/AudioRecorder.kt`, `audio/WhisperFeatureExtractor.kt` all still present; `isMinifyEnabled = false` still set in `android/app/build.gradle.kts`.
  - F10: `shareCleaned()` still copies to clipboard unconditionally.
  - F11: `audioLengthMs = 0` still hardcoded at the `persistTranscription` call site.
  - No `agent-files/` assets exist yet and no `examplesFor` function exists — Prompt 5 is still fully open.
- **External claims re-checked:** LiteRT-LM `v0.13.1` is still the latest stable release; `v0.14.0-alpha.0` (Jun 18) is still tagged pre-release only — R3's "watch for v0.14 GPU unlock" is still open, nothing to act on yet. No confirmed fix found for the ML Kit Prompt API `generateContent()` non-return bug — Prompt 1's job is to re-test this empirically on-device, so proceed with it as written.
- **Path correction (Prompt 1, step 1):** the project's Gradle layout was flattened after this pack was written — root-level `build.gradle.kts`/`settings.gradle.kts` now define the project, `:app` maps to `android/app`, and `gradle/libs.versions.toml` sits at the repo root (confirmed correct as originally written). The module's own build file is at **`android/app/build.gradle.kts`**, not `app/build.gradle.kts` as originally worded — use the full path.

Everything else below is unchanged and ready to run in order: **1 → 2 → 3 → 4 → 5 → 6 → 7** (2 and 7 can slot in anywhere).

---

## Prompt 1 — Prompt API probe (R1, decision gate)

```
Context: This app runs STT via ML Kit GenAI Speech Recognition (SttProbe.kt) and text
cleanup via LiteRT-LM + Gemma 4 E2B on CPU (model/LlmCleanup.kt). On 2026-06-19 the ML Kit
GenAI Prompt API was abandoned because generateContent() never returned on that AICore
build. The API is now public and reportedly performs best on Pixel 10 with Gemini nano-v3.
I want to re-test it before investing further in the LiteRT-LM path.

Task: Add a temporary probe — do NOT touch the existing LlmCleanup path.
1. Check the current ML Kit docs for the GenAI Prompt API artifact name and latest version;
   add it to gradle/libs.versions.toml and android/app/build.gradle.kts.
2. Create android/app/src/main/java/dev/dean/voice/audio/PromptApiProbe.kt modeled on
   SttProbe.kt: checkStatus(), download() if DOWNLOADABLE, then generateContent() using
   CleanupPrompts.buildParts(VoiceIntent.TEXT, testInput) — system prefix as the prompt
   prefix so AICore can cache it, user content as the text part.
3. Wire it to a debug-only button in MainScreen's settings dropdown labeled "Probe Prompt
   API". On tap, run the probe with a hardcoded ~50-word filler-laden test input and log:
   feature status, time-to-first-response, total latency, and the full output under tag
   "PromptApiProbe".
4. Guard against the old failure mode: wrap generateContent() in a 30s timeout and log
   TIMEOUT distinctly.

Acceptance: app builds and installs; tapping the button produces a complete
status/latency/output log line on the Pixel 10 Pro XL, or a clean TIMEOUT log. No changes
to LlmCleanup.kt, TranscribeViewModel cleanup flow, or the share path.
```

**Interpreting the result:** output faithful + latency comparable to or better than E2B-CPU → plan a Phase 3b migration (cleanup on AICore, LiteRT-LM as fallback when AICore throttles). TIMEOUT again → close R1, file it in tracker.md, proceed with LiteRT-LM as primary.

---

## Prompt 2 — Output-handling bugfix (F1)

```
Context: In model/LlmCleanup.kt, stripPreamble() (lines ~186-192) drops every leading line
that ends with ":". That deletes legitimate output — e.g. a NOTES result whose first line
is "Groceries:" followed by a list loses its heading. Separately, clean() runs
response.replace("\\n", "\n") which rewrites literal backslash-n sequences anywhere in the
model output, including inside user content.

Task:
1. Replace the stripPreamble() heuristic: only strip leading lines that match known
   preamble patterns, e.g. Regex("^(here('s| is)|output|cleaned( text)?|sure)\\b.*:\\s*$",
   IGNORE_CASE). Never strip a line that doesn't match, even if it ends with ":".
2. Investigate the replace("\\n", "\n") call: reproduce whether the LiteRT-LM Kotlin API
   actually returns literal backslash-n in Contents.toString(). If yes, scope the
   replacement to that join artifact; if no, delete the call. Document the finding in a
   code comment.
3. Add unit tests (create app/src/test if missing, JUnit4) for stripPreamble covering:
   (a) "Groceries:" + list survives intact, (b) "Here is the cleaned text:" preamble is
   stripped, (c) multi-line preamble, (d) output that is only a heading line, and for the
   newline handling: (e) output containing an intentional literal backslash-n survives.

Acceptance: ./gradlew testDebugUnitTest passes; no behavior change to the happy path
(plain prose in, plain prose out).
```

---

## Prompt 3 — Engine lifecycle + loading state (F3, F8)

```
Context: LlmCleanup is instantiated inside TranscribeViewModel, so the ~8s engine load is
tied to screen entry (MainScreen LaunchedEffect → warmupPromptModel()) and the engine dies
in onCleared(). Worse, isModelReady() returns true when the model FILE exists even if the
engine isn't loaded, so a fast user (especially via the FAB overlay) hits clean()'s lazy
init and sees the "Cleaning" spinner stall ~8s with no explanation.

Task:
1. Promote LlmCleanup to an app-scoped singleton on VoiceApp (lazy, like repository).
   TranscribeViewModel consumes it; remove cleanup.close() from onCleared().
2. Add a StateFlow<EngineState> to LlmCleanup: NotLoaded / Loading / Ready(model) /
   Failed(msg). warmup(), useModel(), and the lazy-init path in clean() all update it.
3. Start warmup from VoiceApp.onCreate() (persisted model choice from
   AppSettingsRepository) AND from VoiceOverlayService start, whichever comes first —
   warmup() is already idempotent.
4. In TranscribeViewModel.runCleanup: if engine state is Loading when the user picks an
   intent, surface a distinct UiState (e.g. Cleaning with a "Loading model…" label or a
   new PreparingModel state) so the stall is visible and explained. Keep the programmatic
   fallback for NotLoaded/Failed with no model file.

Acceptance: cold-start the app, immediately record and pick an intent — UI shows the
model-loading state, then completes; no silent multi-second stall. Rotating/leaving the
screen does not reload the engine (verify via "Loading" log lines appearing only once).
```

---

## Prompt 4 — Token budget safety (F2/R6 prerequisite)

```
Context: LlmCleanup uses maxNumTokens = 2048 — the TOTAL input+output budget (KV-cache
size) for LiteRT-LM. The NOTES system prompt is already ~500 tokens. CleanupPrompts.
buildExampleBlock() will inject up to 10 few-shot examples when agent files ship (next
feature), which at realistic dictation lengths overflows the budget silently.

Task:
1. Add a cheap token estimator (chars/4 heuristic is fine) as a utility, with a comment
   that it's an estimate calibrated for Gemma-family tokenizers.
2. In CleanupPrompts.buildExampleBlock: cap by ESTIMATED TOKENS, not count — take examples
   newest-first while the running estimate stays under an exampleTokenBudget parameter
   (default 600). Keep the take(10) as a secondary cap.
3. In LlmCleanup: before sendMessage, estimate system + user tokens; if estimate exceeds
   ~60% of maxNumTokens (leaving headroom for generation), log a warning with the numbers
   and drop the example block for that call (rebuild prompt without examples) rather than
   risk truncation.
4. Make MAX_NUM_TOKENS an EngineConfig knob wired to a constant per model — keep 2048 for
   now, but leave a commented note that raising it costs KV-cache memory and prefill time.
5. Unit-test the budget logic: examples trimmed to fit; oversized single input drops
   examples; normal input keeps them.

Acceptance: ./gradlew testDebugUnitTest passes; existing no-example behavior unchanged
(runCleanup currently passes no examples).
```

---

## Prompt 5 — Agent files / few-shot wiring (roadmap ⭐)

```
Context: The coachability feature that motivated the Gemma 4 migration. Plumbing exists:
CleanupPrompts.buildParts() injects an example block; LlmCleanup.clean(intent, rawText,
examples) accepts pairs; TranscribeViewModel.runCleanup currently passes nothing. Room has
a training_pairs table (TrainingPairDao) and DataStore has ENABLE_IN_CONTEXT (default
true). Prompt 4's token-budget capping must already be merged.

Task:
1. Curated agent files: load per-intent example files from assets/agent-files/<intent>.md
   (format: repeated "RAW:" / "CLEANED:" blocks separated by blank lines). Parse at first
   use, cache in memory. Create one starter file per intent with 2-3 hand-written examples
   matching my style (short dictation, filler removal, no invented content) — mark them
   clearly as placeholders for me to edit.
2. Captured pairs: in runCleanup's success path, this stays out of scope for now EXCEPT
   add a TODO hook comment where an edited-output capture would save a TrainingPair.
3. Selection: examplesFor(intent) = curated file examples first, then most-recent Room
   training_pairs for that intent, deduped, capped by Prompt 4's token budget. Gate the
   whole thing on AppSettingsRepository.inContextLearningEnabled.
4. Wire runCleanup to pass examplesFor(intent) into cleanup.clean().
5. A/B observability: extend the existing cleanup log block with exampleCount and
   estimated prompt tokens so E2B/E4B × examples/no-examples comparisons are readable
   from logcat.

Acceptance: builds and runs; with agent files present, logcat shows exampleCount > 0 and
cleanup output reflects the examples' style; toggling the in-context setting to false
returns to exampleCount=0 behavior without rebuild.
```

---

## Prompt 6 — Performance batch (F5, F6, F7)

```
Context: Three independent latency wins in the cleanup path (model/LlmCleanup.kt,
ui/TranscribeViewModel.kt).

Task:
1. CPU thread tuning (F6): Backend.CPU() currently uses the default thread count. Add a
   debug-only benchmark: a settings-dropdown action "Benchmark CPU threads" that runs the
   same TEXT cleanup on a fixed ~50-word input with threadCount = default, 4, 5, 6
   (engine reload between runs), logging prefill+decode latency per config under tag
   "CpuBench". Do not change the production default yet — I'll pick from the numbers.
2. Intent routing (F5): in runCleanup, route TEXT and EMAIL inputs shorter than 200 chars
   through programmaticClean() even when the model is ready (tracker.md Phase 3 header
   documents this design; the code drifted). AI_PROMPT and NOTES always use the LLM.
   Make the threshold a named constant.
3. Streaming (F7): switch clean() from sendMessage to sendMessageAsync (Flow<String>),
   collecting into the final result. Update UiState.Cleaning to carry an optional
   partialText and render it progressively. Add cancellation: leaving the cleaning state
   cancels collection (this is also the kill switch for runaway generation). Keep the
   Result type and logging unchanged.

Acceptance: benchmark logs four CpuBench lines; a short TEXT dictation completes in
<100ms via the programmatic path (log line confirms path taken); a NOTES cleanup shows
progressive text during generation and can be cancelled.
```

After running: set the winning `threadCount` in `Backend.CPU(...)` and commit with the benchmark numbers in the message.

---

## Prompt 7 — Hygiene sweep (F9, F10, F11)

```
Context: Dead code from the pre-pivot Whisper/ONNX stack still ships in the APK, release
minification is off, every share copies to the clipboard, and persisted transcriptions
record audioLengthMs = 0.

Task:
1. Delete (F9): model/WhisperInference.kt, model/WhisperTokenizer.kt,
   model/ModelManager.kt, audio/WhisperFeatureExtractor.kt, audio/AudioRecorder.kt, and
   assets/whisper_vocab.json (~967KB). First grep for references outside these files —
   there should be none reachable from the live flow; if any exist, list them and stop.
   Leave python/ and the root whisper_vocab.json untouched (archive).
2. Minify (F9): set isMinifyEnabled = true for release with proguard rules covering Room,
   LiteRT-LM (com.google.ai.edge.litertlm.**), and ML Kit GenAI classes. Build a release
   APK to verify R8 passes. Note in the commit if any keep-rules were needed.
3. Clipboard (F10): in shareCleaned(), stop the unconditional copy. Copy only in the
   dormant deliverTo() fallback path, and mark clips sensitive
   (ClipDescription.EXTRA_IS_SENSITIVE) so previews are hidden.
4. audioLengthMs (F11): record a timestamp when SttProbe emits Listening and compute the
   duration at ReviewTranscript; thread it through runCleanup → persistTranscription
   instead of the hardcoded 0.

Acceptance: debug + release builds pass; app runs (record → clean → share) with no
clipboard chip on the share path; a new Transcription row has audioLengthMs > 0; APK size
drops vs. previous release build (report the delta).
```

---

## Suggested order & session notes

1 (probe, gates strategy) → 2 (bugfix, safe anytime) → 3 (lifecycle) → 4 (budget) → 5 (agent files) → 6 (perf) → 7 (hygiene). Prompts 2 and 7 are independent and can slot anywhere. If Prompt 1 succeeds, re-scope 5 and 6 to whichever engine wins the A/B before running them.
