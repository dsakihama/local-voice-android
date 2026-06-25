# Local Voice Android App – Design & Requirements

**Project:** Personal, sovereign voice-to-text tool for Android (Pixel 10 Pro XL)  
**Philosophy:** Private by default. Local inference only. Develop as-if others might use it, but optimize for personal sovereignty.  
**Date:** 2026-06-16

---

## **1. Product Vision**

A local, on-device voice-to-text tool that turns speech into polished, context-aware text and delivers it seamlessly to your most-used apps. The app learns your speech patterns, your preferred targets, and adapts cleanup based on intent.

**Not:** A general-purpose dictation tool. Not Wispr clone.  
**Is:** Intent-driven, app-aware, learning system that respects your privacy and workflow.

---

## **2. Core User Flow**

```
User taps FAB
  ↓
[Step 1] Select Intent
  → "AI Prompt" / "Text" / "Email" / "Notes"
  ↓
[Step 2] Dictate
  → Mic captures audio (max 60 sec, auto-stop on 2 sec silence)
  → Visual feedback (waveform, red dot)
  ↓
[Step 3] Process
  → Whisper (STT): raw transcription (~2-4 sec)
  → LLM (cleanup): context-specific prompt (~2-5 sec)
  → Total: 4-9 sec, show "Processing..." spinner
  ↓
[Step 4] Select Target App
  → Contextual menu of installed apps, sorted by frequency
  → Apps grouped by category (AI, Comms, PKB)
  → Tap target app
  ↓
[Step 5] Deliver Text
  → Copy to clipboard
  → Attempt Accessibility Service injection into target app
  → If fails → notification with "Paste" button
  → If succeeds → toast "✓ Text injected"
  ↓
User sees polished text in target app → edits if needed → sends/saves
```

---

## **3. Intent Types & Cleanup Prompts**

### **Intent: "ai_prompt"**
Used when sending instructions to Claude, ChatGPT, Perplexity, etc.

**Cleanup objective:** Structure, clarity, context-rich  
**Example:**
- Raw: "hey um claude so like can you help me think about how to um structure a database for a like multi-tenant saas app"
- Cleaned: "Help me design a database schema for a multi-tenant SaaS product. Key considerations: how should different customers with different column requirements work?"

**Prompt template:**
```
You are an AI prompt cleanup specialist. The user is sending instructions to an AI model.

Make the prompt:
- Clear and specific (remove filler words: umm, like, you know, uh)
- Well-structured (use numbered steps if complex, markdown if needed)
- Include necessary context and constraints
- Preserve technical terms and jargon
- Add formatting for readability (markdown, bullet points)

Clean this speech-to-text:
[raw transcript]

Output: Only the cleaned prompt, nothing else.
```

---

### **Intent: "text"**
Used for SMS, WhatsApp, Slack, casual messaging.

**Cleanup objective:** Natural, conversational, concise  
**Example:**
- Raw: "hey like um i was thinking maybe we could grab coffee tomorrow afternoon if you're free"
- Cleaned: "Hey! Wanna grab coffee tomorrow afternoon?"

**Prompt template:**
```
You are a casual messaging cleanup specialist.
Keep the conversational tone. Fix typos and grammar.
Remove filler words (umm, like, uh) but keep it sounding natural.
Keep it short and punchy.
Emoji-friendly: add emojis if they fit the vibe.

Clean this speech-to-text:
[raw transcript]

Output: Only the cleaned message, nothing else.
```

---

### **Intent: "email"**
Used for Gmail, Outlook, formal communication.

**Cleanup objective:** Professional, structured, clear  
**Example:**
- Raw: "hey um so i wanted to reach out about um the timeline for the project and maybe we should like sync up this week"
- Cleaned: "Hi, I wanted to discuss the project timeline. Would you have time to sync up this week?"

**Prompt template:**
```
You are a professional email cleanup specialist.
Tone: polite, clear, direct.
Structure: greeting → request/content → close.
Remove filler words completely.
Fix grammar strictly.
Add appropriate punctuation and capitalization.

Clean this speech-to-text:
[raw transcript]

Output: Only the cleaned email, nothing else.
```

---

### **Intent: "notes"**
Used for Obsidian, Apple Notes, Notion.

**Cleanup objective:** Scannable, structured, preserves ideas  
**Example:**
- Raw: "so um we talked about the new feature roadmap and like the team said we should prioritize the analytics dashboard and um the reporting stuff and also maybe the integrations"
- Cleaned: "Feature Roadmap Priorities:
- Analytics dashboard
- Reporting tools
- Integrations (maybe)"

**Prompt template:**
```
You are a note-taking cleanup specialist.
Format as readable notes with clear structure.
Use bullet points for lists.
Preserve technical terms and jargon.
Be concise and scannable.

Clean this speech-to-text:
[raw transcript]

Output: Only the cleaned notes, nothing else.
```

---

## **4. Target App Detection & Learning**

### **App Categories**

The app surfaces apps in a contextual menu, grouped by category:

#### **AI Apps** (Auto-detected, installed on device)
- Claude (web interface via Chrome)
- ChatGPT (web interface via Chrome)
- Perplexity (web interface or app)
- Local LLM interfaces (if running)

Detection: App package check + deep links
```kotlin
val aiApps = listOf(
    App("Claude", "com.openai.chatgpt"),  // Fallback: web link
    App("ChatGPT", "com.openai.chatgpt"), // Web interface
    App("Perplexity", "ai.perplexity.app"),
)
```

#### **Comms Apps**
- SMS/Messaging (native)
- Email (Gmail, Outlook)
- WhatsApp
- Slack
- Discord
- Telegram

Detection: Native intents + package checks
```kotlin
val commsApps = listOf(
    App("SMS", "com.android.messaging"),
    App("Gmail", "com.google.android.gm"),
    App("Outlook", "com.microsoft.office.outlook"),
    App("WhatsApp", "com.whatsapp"),
    App("Slack", "com.slack"),
    App("Discord", "com.discord"),
)
```

#### **PKB (Personal Knowledge Base)**
- Obsidian
- Apple Notes (via iCloud web if available)
- Notion (web)
- OneNote

Detection: Deep links + web URLs
```kotlin
val pkbApps = listOf(
    App("Obsidian", "md.obsidian"),
    App("Notion", "notion_web_url"),  // Web fallback
)
```

### **Frequency Ranking**

The app learns which target you paste to most frequently and reorders the menu.

**Storage:**
```kotlin
data class AppUsageRecord(
    val targetAppId: String,
    val intent: String,
    val timestamp: Long,
    val userAccepted: Boolean,  // Did user actually paste or dismiss?
)
```

**Ranking logic:**
- Track last 30 days of pastes
- Weight by recency (last 7 days = 1.0x, older = decay)
- Sort menu by frequency
- Examples:
  - "ai_prompt" intent → sort AI apps to top
  - "text" intent → sort Slack + SMS to top
  - "email" intent → sort Gmail to top

**UX example:**
```
User selects "ai_prompt" intent:
  1. Claude (used 8 times in last 7 days) ← Today most-used
  2. ChatGPT (used 3 times in last 7 days)
  3. Perplexity (used 1 time in last 30 days)
  ---
  4. Gmail
  5. Slack
  ...

User selects "text" intent:
  1. Slack (used 12 times in last 7 days) ← Reordered by context
  2. WhatsApp (used 5 times)
  3. SMS (used 2 times)
  ...
```

**No cloud, fully local:** Rankings stored in SQLite database on device.

---

## **5. Architecture & Technical Design**

### **5.1 Hardware Profile: Pixel 10 Pro XL**
- **RAM:** 12GB
- **Storage:** 256GB
- **Processor:** Snapdragon (latest)
- **Constraints:** Battery drain, thermal throttling above 40°C

### **5.2 Component Stack**

**Language:** Kotlin  
**Minimum API:** 31 (Android 12)

**Core dependencies:**
```
Audio capture:
  - Android Media API (AudioRecord)
  - TarsosDSP (audio preprocessing, optional)

Inference:
  - ONNX Runtime for Android (supports Whisper + LLM)
  - TensorFlow Lite (fallback, if ONNX proves heavy)

Text injection:
  - AccessibilityService (primary)
  - InputMethodManager (fallback)
  - Clipboard API (fallback)

UI:
  - Jetpack Compose (modern, minimal boilerplate)
  - Material 3 (design system)

Storage:
  - Room (SQLite wrapper)
  - DataStore (shared preferences, encrypted)

Build:
  - Gradle (Maven Central for dependencies)
```

### **5.3 Data Storage Schema**

```kotlin
// 1. Transcription history (for debugging + analytics)
@Entity(tableName = "transcriptions")
data class Transcription(
    @PrimaryKey val id: String,
    val rawText: String,
    val cleanedText: String,
    val intent: String,
    val targetApp: String?,  // If user pasted
    val timestamp: Long,
    val audioLengthMs: Int,
    val processingTimeMs: Int,
)

// 2. App usage ranking
@Entity(tableName = "app_usage")
data class AppUsageRecord(
    @PrimaryKey val id: String,
    val targetAppId: String,
    val targetAppName: String,
    val intent: String,
    val timestamp: Long,
    val userAccepted: Boolean,  // Did they paste or dismiss?
)

// 3. Training pairs for in-context learning
@Entity(tableName = "training_pairs")
data class TrainingPair(
    @PrimaryKey val id: String,
    val rawText: String,
    val cleanedText: String,
    val intent: String,
    val timestamp: Long,
    val userRating: Float?,  // 0-5 stars, optional
)

// 4. Settings
data class AppSettings(
    val autoStopSilenceMs: Int = 2000,
    val maxDictationLengthSec: Int = 60,
    val selectedLanguage: String = "en-US",
    val enableAccessibility: Boolean = false,
    val enableInContextLearning: Boolean = true,
    val enableAnalytics: Boolean = false,  // Local analytics only
)
```

### **5.4 Model Selection**

**Speech-to-Text (Whisper):**
- Model: `whisper-small-int8` (~150MB)
- Why Small: Better accuracy than Tiny, acceptable latency (2-4 sec)
- Why int8: Quantized for mobile, minimal accuracy loss
- Fallback: If device too constrained, use Tiny (~39MB)

**Post-Processing LLM:**
- Model: `Phi-2-Q4` (~1.5GB) or `Mistral-7B-Q4` (~4GB)
- Start with Phi-2 (faster, acceptable quality)
- Upgrade to Mistral if user feedback suggests needed
- Why Q4 quantization: Fits in RAM, acceptable quality trade-off
- In-context learning: Include 5-10 recent training pairs in every prompt

**Inference Runtime:**
- ONNX Runtime for Android (multi-model support, good mobile optimization)
- Models converted to ONNX format pre-build
- Load models on app startup (cached in RAM)

**Storage footprint:**
- Whisper Small int8: ~150MB
- Phi-2 int4: ~1.5GB
- ONNX Runtime: ~50MB
- Total: ~1.7GB (acceptable)

### **5.5 Audio Processing Pipeline**

```
Microphone input (16kHz PCM)
  ↓
Audio buffering (30-second max buffer)
  ↓
Preprocessing (optional noise suppression via TarsosDSP)
  ↓
Whisper inference (on-device, GPU-accelerated if available)
  ↓
Raw transcription
  ↓
LLM inference (intent-specific prompt + in-context examples)
  ↓
Cleaned text
  ↓
Copy to clipboard + show target app menu
```

**Latency targets:**
- Capture → processing start: <200ms
- Whisper: 2-4 sec
- LLM cleanup: 2-5 sec
- Total user-perceived time: 4-9 sec (acceptable)

### **5.6 Text Injection Strategy**

**Primary (85% success):**
```kotlin
fun injectViaAccessibility(text: String, targetApp: String): Boolean {
    val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (focusedNode != null) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
    return false
}
```

**Fallback (100% success):**
```kotlin
fun fallbackToPaste(text: String) {
    clipboard.setPrimaryClip(ClipData.newPlainText("label", text))
    showNotification("Text copied. Tap to paste.")
}
```

**App-specific handling (v2+):**
- Gmail: Use intent with subject + body
- WhatsApp: Deep link to chat + clipboard paste
- Slack: Deep link to channel + clipboard paste

---

## **6. UI/UX Design**

### **6.1 FAB (Floating Action Button)**

**Default state:**
- Small circular button (56dp)
- Microphone icon
- Always visible, draggable
- Position: bottom-right (user can move)

**Tap behavior:**
```
Tap FAB
  ↓
Show intent selection (bottom sheet, dismissible)
  [AI Prompt] [Text] [Email] [Notes]
  ↓
User taps intent
  ↓
FAB animates to listening state
  - Icon changes to red dot
  - Waveform animation
  - "Listening... Tap to stop" label
  ↓
User stops speaking or taps to stop
  ↓
FAB animates to processing state
  - Icon changes to spinner
  - "Processing..." label (1-3 sec)
  ↓
Show target app menu (bottom sheet)
  [Most recent apps for this intent, grouped by category]
  ↓
User taps target app
  ↓
Text delivery (inject or clipboard)
  ↓
Toast confirmation: "✓ Text injected" or "Text copied. Tap to paste"
```

### **6.2 Target App Menu**

**Layout:**
```
┌─────────────────────────────────────┐
│ Select where to paste               │
│ [X]                                 │
├─────────────────────────────────────┤
│ AI APPS                             │
│ [Claude] [ChatGPT] [Perplexity]    │
├─────────────────────────────────────┤
│ COMMS                               │
│ [Slack] [WhatsApp] [Gmail]          │
├─────────────────────────────────────┤
│ NOTES & PKB                         │
│ [Obsidian] [Notion]                 │
├─────────────────────────────────────┤
│ [ ] Don't show this next time       │
│ (Paste directly to most-used)       │
└─────────────────────────────────────┘
```

**Smart behavior:**
- If user has a single most-used target for this intent, offer checkbox: "Paste directly next time"
- Toggling skips this menu and goes straight to injection/copy
- Can re-enable from settings

### **6.3 Home Screen Widget (v1.5)**

**Small widget (2×2):**
```
┌─────────────────┐
│ 🎤 Voice Notes  │
│                 │
│ Tap to dictate  │
│                 │
│ Today: 5 notes  │
└─────────────────┘
```

**Interaction:**
- Tap → launches full app with FAB ready to listen
- Shows daily counter (motivational)
- Scrollable history of last 3 transcriptions (preview)

### **6.4 Lock Screen Widget (v2)**

**Lock screen widget (2×1):**
```
🎤 Tap to dictate | 📝 Last note...
```

**Behavior:**
- User taps "Tap to dictate" → dictation starts (without unlocking)
- Shows last transcribed text as context
- Requires Android 12+

---

## **7. Feature Roadmap**

### **V1 (MVP, 6-8 weeks)**

**Core:**
- ✅ Whisper STT (Small int8)
- ✅ Intent selection (AI, Text, Email, Notes)
- ✅ LLM cleanup (Phi-2 int4 with intent-specific prompts)
- ✅ In-context learning (store + include 5-10 recent pairs)
- ✅ Target app detection (AI, Comms, PKB groups)
- ✅ App frequency ranking (learn most-used targets)
- ✅ Accessibility Service text injection
- ✅ Clipboard fallback with notification
- ✅ FAB UI + intent selection
- ✅ Basic settings (language, auto-stop timer)

**Out of scope:**
- Personal dictionary learning
- Snippet library
- LoRA fine-tuning
- Lock screen widget
- Analytics
- Cloud sync

---

### **V1.5 (Polish, 4-6 weeks)**

- ✅ Home screen widget (show stats, recent transcriptions)
- ✅ Widget configuration (size, refresh rate)
- ✅ Error recovery (graceful fallback if models fail)
- ✅ Battery monitoring (warn if heavy usage)
- ✅ Improved error messages + troubleshooting guide

---

### **V2 (Personalization, 6-8 weeks)**

- ✅ Lock screen widget (Android 12+)
- ✅ LoRA fine-tuning option ("Personalize model" in settings)
- ✅ Personal dictionary learning (auto-add recurring names/terms)
- ✅ Snippet library (voice shortcuts for common phrases)
- ✅ Language auto-detection (bilingual support)
- ✅ Thermal management (throttle if phone gets hot)
- ✅ Quick Settings tile ("Dictate now" in pull-down menu)

---

### **V3 (Advanced, Future)**

- 🔮 Wear OS integration (Pixel Watch dictation)
- 🔮 App-specific intent inference (detect if in Gmail → assume "email")
- 🔮 Adapter sharing (export your trained LoRA as .lora file)
- 🔮 Launcher shortcuts (long-press app icon → "Dictate to...")
- 🔮 Passive listening (detect headset button, ambient activation)
- 🔮 Custom voice prompts ("Make this formal," "Add emojis")

---

## **8. Privacy & Data Sovereignty**

**Core principles:**
- ✅ No cloud. No analytics collection (local only).
- ✅ No data leaves device (ever).
- ✅ Models loaded locally, inference local.
- ✅ User owns all data (transcriptions, training pairs, app rankings).
- ✅ Encryption at rest (DataStore encrypted).

**User controls:**
- Delete all transcription history anytime
- Opt out of in-context learning
- Disable training pair collection
- Export all data (JSON dump)
- Reset app rankings

**Transparency:**
- Clear settings screen showing what's stored + how much space
- "Data dashboard" showing: transcriptions stored, training pairs, app rankings, total storage used
- Export button (compliance + trust)

---

## **9. Success Metrics** (Personal, not business)

**Usage:**
- Daily dictations (target: 5-10 per day)
- Average session latency (target: <8 sec)
- Text acceptance rate (% of transcriptions user keeps as-is, target: >70%)

**Learning:**
- In-context examples accumulated (target: 50+ per intent)
- App ranking stability (% of top target consistent week-to-week)
- Intent selection consistency (e.g., "ai_prompt" intent always → Claude)

**Quality:**
- Transcription accuracy (no formal measurement, subjective feel)
- Cleanup quality (does LLM output match your style?)
- Injection success rate (% of pastes that land in right place)

---

## **10. Tech Debt & Known Constraints**

**Phone compatibility:**
- Pixel 10 Pro XL: Full support
- Older Pixels (8GB RAM): May struggle with concurrent inference
- Other Android devices: Untested, defer support

**Android fragmentation:**
- Custom ROMs, manufacturer keyboards may block Accessibility Service
- Fallback to clipboard + notification

**Model size:**
- Phi-2 int4 is 1.5GB; on constrained devices, may need smaller model
- Quantization reduces accuracy slightly
- ONNX Runtime adds ~50MB overhead

**Latency:**
- 4-9 sec is acceptable for personal use
- Not competitive with Wispr's cloud-backed speed (~1-2 sec)
- Trade-off: local privacy > speed

---

## **11. Build Checklist (V1)**

**Phase 1: Setup & Core**
- [ ] Kotlin project setup, Gradle config
- [ ] Download + test Whisper Small int8 locally
- [ ] Download + test Phi-2 int4 locally
- [ ] Convert models to ONNX format
- [ ] Integrate ONNX Runtime for Android

**Phase 2: Audio & STT**
- [ ] Audio capture (AudioRecord API)
- [ ] Whisper inference pipeline
- [ ] Handle audio preprocessing (silence trimming, etc.)
- [ ] Test on actual Pixel 10 Pro XL

**Phase 3: LLM & Cleanup**
- [ ] Phi-2 inference pipeline
- [ ] Intent-specific prompt templates (AI, Text, Email, Notes)
- [ ] In-context learning (store + retrieve training pairs)
- [ ] Test output quality for each intent

**Phase 4: Text Injection**
- [ ] Accessibility Service registration + testing
- [ ] App focus detection
- [ ] InputMethodManager text injection
- [ ] Clipboard fallback
- [ ] Notification UI for paste button

**Phase 5: App Detection & Learning**
- [ ] Auto-detect installed AI apps (Claude, ChatGPT, Perplexity)
- [ ] Auto-detect installed comms apps (Slack, WhatsApp, Gmail, SMS)
- [ ] Auto-detect PKB apps (Obsidian)
- [ ] App usage tracking (frequency ranking)
- [ ] Room database setup + migrations

**Phase 6: UI**
- [ ] FAB implementation (Jetpack Compose)
- [ ] Intent selection bottom sheet
- [ ] Target app menu (grouped by category, sorted by frequency)
- [ ] Listening state UI (waveform animation)
- [ ] Processing spinner
- [ ] Toast notifications

**Phase 7: Settings & Permissions**
- [ ] Settings screen (language, auto-stop time, toggle features)
- [ ] Microphone permission request
- [ ] Accessibility permission request
- [ ] Clipboard permission (Android 13+)

**Phase 8: Testing & Polish**
- [ ] Test on Pixel 10 Pro XL (real device)
- [ ] Test app injection (Gmail, Slack, WhatsApp, Obsidian)
- [ ] Test fallback (clipboard + notification)
- [ ] Battery drain measurement
- [ ] Thermal testing (sustained dictation)
- [ ] Error handling + graceful degradation

---

## **12. Success Definition (V1)**

**You can use it daily for:**
- ✅ Dictating messages to Slack, WhatsApp, SMS
- ✅ Drafting emails in Gmail
- ✅ Sending prompts to Claude
- ✅ Capturing notes in Obsidian
- ✅ Text appears in the target app without extra friction
- ✅ Cleanup quality matches your expectation (within 10% manual editing)

**Performance acceptable if:**
- ✅ Total time from start-speaking to text-in-app < 10 sec
- ✅ Battery drain < 5% per day with 5-10 dictations
- ✅ Phone doesn't thermally throttle (stays <40°C)

**You'll know it's working when:**
- ✅ You reach for it as a default input method (not a novelty)
- ✅ You stop editing most outputs immediately (80%+ acceptance rate)
- ✅ The app learns your targets and reorders them correctly

---

## **13. Open Questions for Next Iteration**

- Should LoRA training happen automatically (weekly background task) or manual (user-triggered)?
- For Mistral vs Phi-2: When should we upgrade? (Usage volume? User feedback threshold?)
- Should in-context examples be weighted by user rating (star system)?
- Should we expose the cleanup prompt to user for customization (v2)?
- How to handle private apps (banking, password managers) that block Accessibility Service?

---

## **Appendix: Example Flows**

### **Example 1: Send message to Slack**

```
1. User taps FAB
2. Intent selection appears
3. User taps [Text]
4. FAB enters listening mode (waveform animation)
5. User speaks: "hey um so like we should probably sync on the roadmap thing maybe tomorrow afternoon"
6. User stops or taps to finish
7. Processing spinner (4-9 sec)
8. Cleanup produces: "Hey, should we sync on the roadmap tomorrow afternoon?"
9. Target app menu appears:
   - Slack (8 uses in last 7 days) ← top
   - WhatsApp (3 uses)
   - SMS (2 uses)
10. User taps Slack
11. App attempts Accessibility Service injection
12. Success: Text appears in focused Slack message field
13. Toast: "✓ Text injected to Slack"
14. User sees: "Hey, should we sync on the roadmap tomorrow afternoon?" ready to send
15. User edits (if needed) → sends
```

### **Example 2: Send prompt to Claude**

```
1. User taps FAB
2. Intent selection appears
3. User taps [AI Prompt]
4. FAB enters listening mode
5. User speaks: "um claude can you help me think through the data model for um a multi-tenant saas where um different customers have different column requirements and like we need to make it efficient"
6. User stops
7. Processing spinner
8. LLM (with AI intent prompt) produces:
   "Claude, help me design a data model for a multi-tenant SaaS platform. Requirements:
   - Different customers with different column requirements
   - Efficient for querying
   - Scalable for hundreds of customers
   
   Should I use a wide table with dynamic columns, or separate tables per customer?"
9. Target app menu (AI apps at top):
   - Claude (12 uses in last 7 days)
   - ChatGPT (2 uses)
   - Perplexity (1 use)
10. User taps Claude
11. App detects Claude web interface (not native app)
12. Copies to clipboard + sends Chrome intent to Claude page
13. Notification: "Text copied. Tap to paste in Claude"
14. User switches to Chrome (or it's already open)
15. Taps notification → auto-pastes
16. Polished prompt appears in Claude input field
17. User sees structured, clear prompt → sends
```

---

**Document Status:** Draft (v0.1)  
**Next Review:** After V1 implementation begins  
**Owner:** Dean Sakihama
