# Mac Version Considerations

Functionality and UX patterns validated on the Android version worth carrying forward to a Mac implementation. Model selection will differ — Mac has significantly more resources so larger models (e.g. Gemma 3 4B+ or better) are viable.

---

## UX

- **Manual stop button during recording** — silence detection alone is insufficient for longer voice inputs. A visible stop button should persist throughout the entire recording state, including while partials are streaming in. Validated on Android: stop button disappears when partial text starts rendering, which is confusing. Fix: keep stop button visible at all times during active recording.

- **STT custom vocabulary** — ML Kit STT on Android has no custom vocabulary support. Salutations and proper nouns at the start of an utterance are frequently misrecognized (e.g. "Hi Uncle" → "hackle"). Mac STT options (Whisper, Apple Speech framework) may support custom vocabulary or post-processing word substitution to fix known problem phrases.

---

## Model

- Larger models viable — no need to constrain to 1B int4. Evaluate Gemma 3 4B+ or comparable.
- Prompt templates from `CleanupPrompts.kt` are a good starting point but will need retuning for a larger model's capabilities and response style.
