# Local Voice

A fully offline, on-device dictation app for Pixel. Speak → the transcript gets cleaned up by a local LLM → drop the result into any app via the native share sheet. No audio, no text, and no prompts ever leave the device.

## Why

Voice-to-text on Android is usually a round trip through someone else's cloud, and the "cleanup" step (stripping filler words, structuring dictated notes) is either absent or another network call. Local Voice does both entirely on-device, using the Tensor NPU/TPU already sitting in a Pixel phone — no account, no network permission, no server.

## How it works

```
🎙️  tap FAB, speak
     ↓
ML Kit GenAI Speech Recognition (on-device, via AICore)
     ↓
Gemma 4 (E2B/E4B) running on LiteRT-LM — cleans filler words,
structures notes, stays faithful to what was actually said
     ↓
📤  native Android share sheet → Messages, Gmail, Obsidian, anywhere
```

Every session is persisted locally (Room) so cleanup quality can be tuned over time — the intent behind the `training_pairs` table is to feed the model corrected examples of its own past cleanups (few-shot steering) rather than relying on free-form generation alone.

## Design decision worth calling out

The project didn't start on this stack. It began with ONNX Runtime + Whisper (STT) + Phi-2 (cleanup) run through a custom conversion pipeline — see [`python/`](python) for the conversion scripts and [`design/Requirements/pivot20260619.md`](design/Requirements/pivot20260619.md) for the full writeup. That approach worked but was slow (2–8 tok/sec on NNAPI) and heavy to maintain. It was replaced with Android's ML Kit GenAI APIs and on-device Gemma, which run on dedicated silicon instead of a generic NN accelerator path — ~940 tok/sec prefill on the same device. The cleanup model itself has also moved twice since (Gemma 3 1B → Gemma 4 E2B/E4B) after the smaller model proved unfaithful to the source transcript; `tracker.md` has the running log of what was tried and why each call was made.

## Status

Active, unreleased side project — not on the Play Store, no signing/distribution setup. Currently mid-build on the LLM-cleanup polishing phase (see `tracker.md` for the live task list). Expect rough edges and dead code paths kept around for A/B comparison.

## Requirements

- A Pixel phone with AICore / Gemini Nano support (built and tested on a Pixel 10 Pro XL, Tensor G5)
- Android 12+ (`minSdk 31`)
- Android Studio to build and deploy — this is a dev build, not a distributable APK

## Stack

- Kotlin, Jetpack Compose, Material 3
- Room (local persistence — transcriptions, app-usage ranking, training pairs)
- ML Kit GenAI Speech Recognition (Advanced/Alpha, on-device via AICore)
- LiteRT-LM running Gemma 4 (E2B/E4B, runtime-toggleable) for transcript cleanup
- Android AccessibilityService for optional direct text injection into the focused field

## Getting started

```bash
git clone <this-repo>
cd local-voice
./gradlew installDebug
```

This builds and installs the app, but cleanup won't run yet — **model weights aren't in this repo** (see below). The app needs microphone, accessibility (optional, for direct injection), and notification permissions on first run. AICore-backed features only function on supported Pixel hardware — see [`docs/model-and-pipeline-review-2026-07-05.md`](docs/model-and-pipeline-review-2026-07-05.md) for the current known-issues list.

### Getting the cleanup model onto a device

The Gemma 4 `.litertlm` weights (E2B ~2.6GB, E4B ~3.7GB) aren't bundled in the repo, the APK, or downloaded at runtime — they're pulled and staged onto the device by hand:

1. Download the `-it` (instruction-tuned) `.litertlm` file(s) from [HF `litert-community`](https://huggingface.co/litert-community) — `gemma-4-E4B-it-litert-lm` and/or `gemma-4-E2B-it-litert-lm`. Skip any `-web.litertlm` files (browser/WebGPU only).
2. Push to the device and move into the app's private storage:
   ```bash
   adb push gemma-4-E4B-it.litertlm /data/local/tmp/
   adb shell run-as dev.dean.voice cp /data/local/tmp/gemma-4-E4B-it.litertlm /data/data/dev.dean.voice/files/
   ```
3. Both E2B and E4B can be present on-device at once — the in-app model toggle switches between them without a rebuild.

`LlmCleanup.kt` prints this exact sequence in its error message if it can't find the model file, so a missing model fails loud rather than silently.

## License

MIT — see [LICENSE](LICENSE).
