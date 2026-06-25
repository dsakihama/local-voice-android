# Requirement & Recommendation: Push Captured Text into Another App via the Native Share Sheet

## Context

The sending app captures text and hands it to Android's native share sheet so the user can route it into another app (e.g. a messaging conversation) and send it manually. The sending app owns only the **send** side; the receiving apps are third-party and not under our control.

---

## Requirements

### R1 — Capture text within the app
The app must capture the text to be shared from its own UI or logic (typed field, text selection, or generated output) and hold it as a string prior to invoking the share flow.

### R2 — Hand text to the system share sheet
The app must launch the Android system chooser (`ACTION_SEND`) with the captured text carried in `Intent.EXTRA_TEXT` and the MIME type set to `text/plain`, so that messaging, notes, and email targets surface correctly.

### R3 — Always present the chooser
The share flow must wrap the intent in `Intent.createChooser(...)` so the picker is always shown and Android does not silently route to a default target.

### R4 — Respect receiver autonomy
The design must accept that the **receiving app decides where shared text lands**. There is no supported Android mechanism to inject text into a conversation thread that merely happens to be open in an app we do not own. Targeting a specific conversation requires supplying a recipient/address, not inheriting on-screen state.

---

## Recommendations

### Baseline implementation (R1–R3)

```kotlin
// R1: capture
val captured = myTextField.text.toString()   // or selected/generated text

// R2 + R3: hand to system chooser
val send = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, captured)
}
startActivity(Intent.createChooser(send, "Share to…"))
```

**Notes**
- `type = "text/plain"` controls which apps appear in the sheet — required.
- `EXTRA_SUBJECT` / `EXTRA_TITLE` are optional and used by some targets (e.g. email subject); harmless to apps that ignore them.

---

### Prioritizing specific targets in the share sheet

The system sheet's own ranking (usage, Direct Share, on-device ML) **cannot be globally reordered** by the app. The following levers shape what appears and what is emphasized.

#### Lever 1 — Pin preferred targets to the top: `EXTRA_INITIAL_INTENTS` (API 21+)
Closest mechanism to "prioritize these apps." Pinned intents render above the ranked list.

```kotlin
val base = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, captured)
}

val whatsApp = Intent(base).setPackage("com.whatsapp")

val chooser = Intent.createChooser(base, "Share to…")
chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(whatsApp))
startActivity(chooser)
```
For clean labels/icons on pinned entries, wrap each in a `LabeledIntent` after resolving the component via `PackageManager`.

#### Lever 2 — Remove unwanted targets: `EXTRA_EXCLUDE_COMPONENTS` (API 24+)
Prunes noise so preferred targets stand out by subtraction.

```kotlin
chooser.putExtra(
    Intent.EXTRA_EXCLUDE_COMPONENTS,
    arrayOf(ComponentName("com.example.unwanted", "com.example.unwanted.ShareActivity"))
)
```

#### Lever 3 — Build a custom chooser
Full control over which apps show and in what order, at the cost of rebuilding the UI and losing the native sheet's look and its Direct Share thread shortcuts.

```kotlin
val resolvers = packageManager.queryIntentActivities(base, 0)
// filter to priority packages, build custom list or LabeledIntents
```

**Trade-off:** Levers 1 and 2 keep the real system sheet (and its feel) while nudging it; Lever 3 gives total control but you maintain a mini share UI. For prioritizing a couple of targets, `EXTRA_INITIAL_INTENTS` alone is usually sufficient.

---

### Landing text in a specific pre-filled conversation

When the recipient is known (or can be requested), these target a specific thread with the body pre-filled — the user taps send. These are **per-app / per-scheme** and require a recipient.

**SMS (default SMS app, conversation for that number):**
```kotlin
val sms = Intent(Intent.ACTION_SENDTO).apply {
    data = Uri.parse("smsto:" + Uri.encode(phoneNumber))
    putExtra("sms_body", bodyText)
}
startActivity(sms)
```

**WhatsApp deep link:**
```kotlin
val uri = Uri.parse("https://wa.me/$number?text=${Uri.encode(text)}")
startActivity(Intent(Intent.ACTION_VIEW, uri))
```

#### Combine: top placement + pre-filled thread
A pinned `EXTRA_INITIAL_INTENTS` entry can carry a **more specific** intent than the base `ACTION_SEND`. Pin a target using a deep link or recipient-targeted `SENDTO` to get both top placement **and** the pre-filled-thread behavior in a single tap.

---

### Landing text into a PKB / note app (Obsidian)

Note apps differ fundamentally from messaging apps for this purpose. Messaging targets are addressed by **recipient**, so an already-open thread is unreachable. Obsidian is addressed by **action + content**, so the captured text can be filed directly into a *new note* without inheriting any on-screen state. This is a documented, first-class capability — not a workaround.

#### R5 — Deterministic new-note creation (Obsidian)
When the target is Obsidian (or another PKB exposing a content-bearing URI), the app should create a new note directly via the `obsidian://new` URI, carrying the captured text in the `content` parameter, rather than relying on the share sheet's in-app routing dialog.

#### Path A — Share sheet (`ACTION_SEND`)
Obsidian registers as a `text/plain` share target, so captured text appears when the user selects Obsidian from the system chooser. Where it lands (new note, append to daily note, vault choice) is resolved by a dialog **inside Obsidian**, by the user. Works everywhere; less deterministic.

#### Path B — Obsidian URI (`obsidian://new`) — recommended for "new note with my text"

```kotlin
val vault = "MyVault"
val title = "Captured ${System.currentTimeMillis()}"
val body  = captured

val uri = Uri.parse(
    "obsidian://new" +
    "?vault="   + Uri.encode(vault) +
    "&name="    + Uri.encode(title) +
    "&content=" + Uri.encode(body)
)
startActivity(Intent(Intent.ACTION_VIEW, uri))
```

**Parameter reference**

| Parameter | Purpose |
|---|---|
| `vault` | Target vault by name or vault ID. If omitted, the last-focused vault is used. Specify when multiple vaults exist. |
| `name` | Note title. Can be combined with `file`/`path` for folder placement. If omitted, an "Untitled" note is generated. |
| `file` / `path` | Note name or full path from vault root (folder placement). `path` overrides `vault` + `file` by searching for the most specific matching vault. |
| `content` | The note body — i.e. the captured text. |
| `clipboard` | Flag: pull content from the system clipboard instead of `content`. |
| `append` | If the named note exists, add the text to the **end** of the file. |
| `prepend` | If the named note exists, add the text to the **top** of the file. |
| `silent` | Create the note without bringing it to the foreground. |
| `x-success` / `x-error` | x-callback-url hooks; Obsidian returns the created note's `name`, `url`, and `file` to the calling app on success. |

**Encoding requirement:** every value must be URI-encoded (`Uri.encode(...)`). Reserved characters in the body — notably `/` (path) and `#` (heading/block) — will break URI parsing if left raw.

#### Combine with prioritization
The `obsidian://new` URI can be pinned via `EXTRA_INITIAL_INTENTS` (wrapped in `Intent.ACTION_VIEW`) to surface a one-tap "New Obsidian note" entry at the top of the system share sheet — combining top placement with deterministic new-note creation.

---

---

### Restricting the share sheet to installed AI apps

Android has **no "AI app" category**. The share sheet filters by declared capability (MIME type / intent filters), not by app purpose — every AI app registers as a generic `text/plain` receiver, indistinguishable from notes or email clients. There is therefore no system flag for "AI apps only." The result is achieved by inverting the model: the app maintains its own **allowlist** of AI package names, checks which are installed, and presents only those.

#### R6 — AI-only target set
When the user shares to "AI," the app must present only allowlisted AI apps that are actually installed, and must handle the case where none are installed.

#### Manifest requirement (Android 11+ / API 30+)
Package visibility is filtered since API 30. Without a `<queries>` declaration, install checks silently return nothing and the sheet renders empty. Declare the intent the app probes for:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.SEND" />
        <data android:mimeType="text/plain" />
    </intent>
</queries>
```
(Alternatively, list each AI package explicitly with `<package android:name="…"/>` entries — but the intent form above is more flexible if the allowlist is runtime-configurable.)

#### Implementation

```kotlin
// Starting allowlist — VERIFY each against its Play Store URL's id= param.
// Prefer a configurable list over hardcoding.
val aiPackages = listOf(
    "com.openai.chatgpt",            // ChatGPT
    "com.anthropic.claude",          // Claude
    "com.google.android.apps.bard",  // Gemini (legacy "bard" package)
    "com.microsoft.copilot",         // Copilot
    "ai.perplexity.app.android",     // Perplexity (confirmed)
    "ai.x.grok"                      // Grok
)

val base = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, captured)
}

val installed = aiPackages.mapNotNull { pkg ->
    val probe = Intent(base).setPackage(pkg)
    if (packageManager.resolveActivity(probe, 0) != null) probe else null
}

when {
    installed.isEmpty() -> { /* no AI apps installed — show message or offer Play Store */ }
    else -> {
        val chooser = Intent.createChooser(installed.first(), "Share to AI…")
        chooser.putExtra(
            Intent.EXTRA_INITIAL_INTENTS,
            installed.drop(1).toTypedArray()
        )
        startActivity(chooser)
    }
}
```

Because every chooser entry is package-restricted, **only allowlisted, installed AI apps appear** — no other app can leak in.

#### Verifying package names
An app's package name is the `id=` parameter in its Play Store URL (e.g. `play.google.com/store/apps/details?id=ai.perplexity.app.android`). Use this to confirm each allowlist entry at release time.

#### Design notes
- **Hardcoded allowlist = maintenance debt.** New AI apps launch and package names drift (Gemini still ships under the legacy `bard` package). Make the list configurable or add a Play Store verification step to the release checklist.
- **Empty-state handling is required.** If the user has no allowlisted AI apps installed, the sheet would otherwise render blank. Handle explicitly (message, or deep-link to a Play Store listing).
- **Native chooser vs. custom picker.** The code above keeps the real system sheet filtered to AI apps. For full control of appearance/order, drop `createChooser`, present a custom list, and launch via `setComponent(...)` — same control-vs-rebuild trade-off noted elsewhere.

---

## Decision Summary

| Goal | Mechanism | Keeps native sheet feel | Needs recipient |
|---|---|---|---|
| Share text anywhere | `ACTION_SEND` + `createChooser` | Yes | No |
| Prioritize specific apps | `EXTRA_INITIAL_INTENTS` | Yes | No |
| Hide specific apps | `EXTRA_EXCLUDE_COMPONENTS` | Yes | No |
| Full control of list/order | Custom chooser via `PackageManager` | No | No |
| Land in specific pre-filled thread | `ACTION_SENDTO` / per-app deep link | No (direct) | Yes |
| Top placement **and** pre-filled thread | Pinned specific intent in `EXTRA_INITIAL_INTENTS` | Yes | Yes |
| Send text to Obsidian (user chooses landing) | `ACTION_SEND` + `createChooser` → Obsidian | Yes | No |
| Create a **new Obsidian note** with the text | `obsidian://new?content=…` via `ACTION_VIEW` | No (direct) | No |
| Restrict share sheet to **installed AI apps** | Allowlist + `resolveActivity` filter + `EXTRA_INITIAL_INTENTS` | Yes | No |

## Known Constraints

- The system share sheet's ranking cannot be globally overridden.
- There is no API to inject text into whatever conversation is currently open in an app we do not own; conversations are addressed by recipient, not by on-screen state.
- Direct-to-thread targeting (`SENDTO`, deep links) is per-app and requires the recipient address.
- `EXTRA_EXCLUDE_COMPONENTS` requires API 24+; `EXTRA_INITIAL_INTENTS` requires API 21+.
- **Obsidian `obsidian://new` values must be URI-encoded**; raw `/` or `#` in the body will break the URI.
- **Obsidian Android cold-start quirk:** when the app is fully closed, `obsidian://new` has been reported to open the last-viewed note instead of creating a new one. Specify `vault` explicitly, test the closed-app state directly, and consider the `ACTION_SEND` share path as a fallback if the edge case affects the flow.
- **No "AI app" category exists in Android.** AI-only targeting depends on a maintained allowlist of package names, not a system filter.
- **Package visibility (API 30+):** a `<queries>` declaration is mandatory or install checks return nothing and the AI sheet renders empty.
- **Allowlist drift:** AI app package names change over time (e.g. Gemini still ships under the legacy `com.google.android.apps.bard` package); verify against each app's Play Store `id=` parameter at release.
