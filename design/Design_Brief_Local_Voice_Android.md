# Design Brief: Local Voice Android App

**Project:** Personal sovereign voice-to-text tool (Pixel 10 Pro XL)  
**Status:** Pre-development design phase  
**Audience:** Design team handoff

---

## **Executive Summary**

A voice-to-text tool that adapts to context. User dictates → app intelligently cleans text based on intent (AI prompt vs. casual text vs. email) → user selects target app from a smart menu → text lands in the right place.

**Core UX principle:** Minimize friction. Three taps max (select intent, dictate, select target). Visually minimal so the FAB feels like a system tool, not another app.

---

## **User Flow (Simplified)**

```
TAP FAB
  ↓
[Intent: AI / Text / Email / Notes]
  ↓
DICTATE (with visual feedback)
  ↓
[Target app menu: Claude, Slack, Gmail, etc.]
  ↓
TEXT APPEARS IN APP
```

**Time from tap to text in target app:** 5-15 seconds total.

---

## **Design System Requirements**

### **Visual Language**
- Minimal, system-native aesthetic (Material Design 3)
- Microphone as primary icon throughout
- Color: Single accent color (blue/purple recommended)
- Typography: System fonts (Roboto on Android)
- Dark mode support required

### **Component Inventory**

| Component | State | Notes |
|-----------|-------|-------|
| FAB | Default, Listening, Processing | Always visible, draggable |
| Intent selector | Sheet, tabs | Bottom sheet, 4 options |
| Waveform animation | Playing | Shows audio input in real-time |
| Processing spinner | Indeterminate | 4-9 sec processing time |
| App menu | Grouped, sorted | AI / Comms / Notes sections |
| Toast notifications | Success / error | Simple confirmation |
| Widget | Home screen, lock screen | v1.5+ |

---

## **Key Flows**

### **Flow 1: Happy Path (Success)**

```
┌─────────────────────────┐
│                         │
│   App is open (any)     │
│   User focused on text  │
│                         │
│       [🎤 FAB]          │  ← Bottom right, visible
│                         │
└─────────────────────────┘
           ↓
┌─────────────────────────────────────┐
│  Select Intent (Bottom Sheet)       │
│  ─────────────────────────────────  │
│  [ AI Prompt ]                      │
│  [ Text Message ]                   │
│  [ Email ]                          │
│  [ Notes ]                          │
│  [                    Dismiss]      │
└─────────────────────────────────────┘
           ↓
┌─────────────────────────┐
│                         │
│   Listening...          │
│   [🔴]   (red dot)      │
│   ≈≈≈≈≈ (waveform)      │
│                         │
│   "Tap to stop"         │
│                         │
└─────────────────────────┘
           ↓
┌─────────────────────────┐
│                         │
│   Processing...         │
│   [⟳]  (spinner)        │
│                         │
│   (4-9 seconds)         │
│                         │
└─────────────────────────┘
           ↓
┌──────────────────────────────────────┐
│  Select Target (Bottom Sheet)        │
│  ──────────────────────────────────  │
│  AI APPS                             │
│  ┌──────┐ ┌────────┐ ┌──────────┐   │
│  │Claude│ │ChatGPT │ │Perplexity│   │
│  └──────┘ └────────┘ └──────────┘   │
│                                      │
│  COMMS                               │
│  ┌──────┐ ┌────────┐ ┌──────────┐   │
│  │Slack │ │WhatsApp│ │Gmail     │   │
│  └──────┘ └────────┘ └──────────┘   │
│                                      │
│  PKB                                 │
│  ┌──────┐ ┌────────┐                 │
│  │Obsidian│ │Notion │                │
│  └──────┘ └────────┘                 │
│  ☐ Don't show next time              │
└──────────────────────────────────────┘
           ↓
┌─────────────────────────┐
│                         │
│   ✓ Text injected      │
│   to Slack             │
│   (Toast, 2 sec)       │
│                         │
└─────────────────────────┘
           ↓
┌─────────────────────────┐
│  Slack (focused)        │
│                         │
│  [Polished text in    │
│   message field]       │
│                         │
│  [Send]                 │
└─────────────────────────┘
```

### **Flow 2: Fallback (Clipboard)**

```
[Same as above until "Select Target"]
           ↓
[Injection fails / app blocks]
           ↓
┌──────────────────────────────────────┐
│  Text copied to clipboard            │
│  [x]                                 │
│  ──────────────────────────────────  │
│                                      │
│  "Text is ready. Tap to paste."      │
│                                      │
│  [Paste]           [Done]            │
└──────────────────────────────────────┘
           ↓
[User taps Paste]
           ↓
[Text pastes into target app]
```

---

## **Component Designs**

### **FAB (Floating Action Button)**

**Size:** 56dp diameter (standard Material 3)  
**Icon:** Microphone (filled when listening, outline default)  
**Color:** Accent color (teal / blue recommended)  
**Shadow:** Material elevation 6  
**Behavior:**
- Always visible, draggable (user can reposition)
- Bottom-right corner (default)
- Stays on top of all apps (system overlay)

**States:**
1. **Default** — Microphone outline, accent color
2. **Listening** — Microphone filled + pulsing, red (visual urgency)
3. **Processing** — Spinner overlay, accent color
4. **Success** — Check mark (1 sec), then resets to default

**Animation:** Smooth state transitions (300ms)

---

### **Intent Selection (Bottom Sheet)**

**Appearance:**
- Material 3 bottom sheet
- 4 equal buttons (grid 2x2)
- Icon + label below icon
- Dismiss overlay (tap outside to close)

**Button design:**
```
  [🤖]
 AI Prompt
 
[💬]
 Text Message

[📧]
 Email

[📝]
 Notes
```

**Behavior:**
- Tap button → dismiss sheet, begin listening
- Swipe down to dismiss (no selection)
- No selection = no dictation starts

---

### **Listening State**

**Visual feedback (critical for UX):**
```
┌─────────────────────┐
│                     │
│      [🔴]           │  Red dot (indicates recording)
│      ≈≈≈≈≈           │  Waveform (responds to voice)
│      ≈≈≈≈≈           │
│                     │
│  "Listening..."     │  Subtext
│  "Tap to stop"      │
│                     │
└─────────────────────┘
```

**Requirements:**
- Waveform animates in real-time (responds to audio input)
- Red color (not accent color) for visual distinction from normal state
- Audio level indicator (height of waveform bars = volume)
- Clear "tap to stop" guidance
- No other UI elements visible (full-screen focus)

**Timeout:**
- Auto-stops after 2 seconds of silence
- 60-second max duration (hard cap)
- Graceful transition to processing state

---

### **Processing State**

**Visual feedback:**
```
┌─────────────────────┐
│                     │
│      [⟳]            │  Spinning loader
│                     │
│  "Processing..."    │
│  "4-9 seconds"      │
│                     │
└─────────────────────┘
```

**Requirements:**
- Indeterminate spinner (Material 3 style)
- Estimated time (optional, helpful)
- Can't be dismissed (forces user to wait)
- Non-blocking (user can do other things)

---

### **Target App Menu (Bottom Sheet)**

**Layout:**
```
┌──────────────────────────────────────┐
│ Select where to paste        [x]     │
│ ──────────────────────────────────── │
│                                      │
│ AI APPS (header)                     │
│ ┌────────┐ ┌────────┐ ┌────────┐    │
│ │ Claude │ │ChatGPT │ │ Perp.  │    │
│ │  [12]  │ │  [3]   │ │  [1]   │    │
│ └────────┘ └────────┘ └────────┘    │
│                                      │
│ COMMS (header)                       │
│ ┌────────┐ ┌────────┐ ┌────────┐    │
│ │ Slack  │ │WhatsApp│ │ Gmail  │    │
│ │  [8]   │ │  [5]   │ │  [2]   │    │
│ └────────┘ └────────┘ └────────┘    │
│                                      │
│ PKB (header)                         │
│ ┌────────┐ ┌────────┐                │
│ │Obsidian│ │ Notion │                │
│ │  [4]   │ │  [1]   │                │
│ └────────┘ └────────┘                │
│                                      │
│ ☐ Paste directly next time to       │
│   [Top app choice]                   │
│                                      │
└──────────────────────────────────────┘
```

**Design notes:**
- Cards with app icon, name, and usage count (motivation)
- Sorted by frequency (most-used at top)
- Grouped by category (visual scannability)
- Checkbox at bottom: "Don't show next time" (if user has dominant target)
- Tap any app → immediate paste (no confirm step)

**App cards:**
- Icon + label
- Small usage counter (builds trust: "This works, I use it")
- Slight tap feedback (ripple)
- No animation needed (quick selection)

---

### **Success / Error Toasts**

**Success (default):**
```
✓ Text injected to Slack
[Dismiss in 2 sec or swipe away]
```

**Error (fallback):**
```
Text copied. Tap to paste.
[Dismiss or swipe away]
```

**Style:**
- Material 3 toast (bottom of screen)
- Icon + text, left-aligned
- Subtle shadow / elevation
- Auto-dismiss after 2-3 sec
- Swipe to dismiss immediately

---

## **Home Screen Widget (v1.5)**

**Size:** 2×2 (minimal)  
**Layout:**
```
┌──────────────────┐
│  🎤 Voice Notes  │
│                  │
│  Tap to dictate  │
│                  │
│  Today: 5 notes  │
└──────────────────┘
```

**Interaction:**
- Tap → launches app with FAB ready
- Updates counter throughout day (motivational)
- Scrollable preview of last 3 transcriptions (optional)

**Design notes:**
- Consistent with app branding
- Simple, glanceable
- Shows value (daily usage count)

---

## **Lock Screen Widget (v2)**

**Size:** 2×1 (lock screen-friendly)  
**Layout:**
```
🎤 Tap to dictate | 📝 "Last note preview..."
```

**Interaction:**
- Tap left side → dictation starts (without unlocking)
- Tap right side → shows full last note
- No unlock required (system-level permission)

---

## **Design Decisions & Rationale**

| Decision | Rationale |
|----------|-----------|
| FAB for always-on access | Works in any app, no integration needed |
| Bottom sheet for menus | Material 3 standard, swipe-friendly on mobile |
| Intent selection first | User controls cleanup quality (garbage in = garbage out) |
| Target app menu with frequency | Reduces friction: most-used targets at top |
| Waveform animation | User feedback = confidence that audio is capturing |
| Red dot during listening | Visual urgency, clear state distinction |
| Clipboard fallback UI | Transparent about when injection fails |
| No personal info visible | Privacy: never show conversation content in UI |

---

## **Accessibility Requirements**

- **Screen reader support:** All buttons, labels, states announced clearly
- **Color contrast:** WCAG AA minimum (4.5:1 for text)
- **Text sizing:** Respect system text size settings
- **Touch targets:** 48dp minimum (not 56dp FAB)
- **No color-only indicators:** Use text + icon for status
- **Haptic feedback:** Optional vibration on state changes (can be disabled)

---

## **Animation & Microinteractions**

**Principle:** Minimal, purposeful. No fluff.

| Interaction | Animation | Duration |
|-------------|-----------|----------|
| FAB default → listening | Scale + color change | 200ms |
| Listening → processing | Icon swap + spinner | 200ms |
| Waveform bars | Real-time follow audio level | N/A |
| Success toast | Slide up + fade out | 200ms + 2s dwell |
| Sheet swipe | Follow finger, snap on release | Varies |

---

## **Color Palette** (Recommendations)

- **Primary (Accent):** Teal or Blue (#0D47A1 or #00897B)
- **Background:** White (light) / #121212 (dark)
- **Surface:** Slightly elevated gray
- **Error/Alert:** Red (#D32F2F)
- **Success:** Green (#388E3C) — optional, use checkmark icon instead
- **Inactive/Disabled:** Gray (#999999)

---

## **Typography** (Material 3)

- **Headline:** Roboto 28px Bold (intent selector title)
- **Title:** Roboto 20px Bold (section headers)
- **Body:** Roboto 16px Regular (labels, descriptions)
- **Label:** Roboto 14px Medium (button text)
- **Small:** Roboto 12px Regular (secondary text, counts)

---

## **Responsive Design Notes**

- **Landscape mode:** Sheet menus adapt to wider screens (grid 3x2 instead of 2x2 for apps)
- **Small screens (<6"):** FAB positioned higher (don't block input area)
- **Large screens (>6"):** Content centered, FAB stays anchored

---

## **Dark Mode**

- Surface colors invert
- Text contrast maintained (white text on dark)
- Accent color unchanged (no desaturation)
- No light flash on state changes (smooth transitions only)

---

## **Error States & Edge Cases**

| Error | User sees | Recovery |
|-------|-----------|----------|
| Mic access denied | "Microphone permission needed" in settings | Direct to system settings |
| Accessibility blocked | Toast: "Clipboard fallback" | Auto-pastes to clipboard |
| Processing fails | "Something went wrong. Try again?" | Retry button, dismissible |
| No audio detected | "No audio detected. Tap again." | Allows retry |
| Target app closed | "App not found" | Show other apps, let user choose |

---

## **Deliverables Checklist**

**Phase 1 (Now):**
- [ ] High-fidelity mockups of all flows (Figma/design tool)
- [ ] Component library (FAB, sheets, buttons, cards)
- [ ] Animation specs (Framer or After Effects)
- [ ] Accessibility checklist (WCAG AA compliance)
- [ ] Design system (colors, typography, spacing)

**Phase 2 (v1.5, after MVP feedback):**
- [ ] Home screen widget mockups
- [ ] Widget interaction states

**Phase 3 (v2):**
- [ ] Lock screen widget mockups
- [ ] Quick Settings tile design

---

## **Handoff Notes for Engineering**

1. **FAB dragging:** Should persist position in SharedPreferences (remember user's preferred location)
2. **Accessibility overlay:** Requires `SYSTEM_ALERT_WINDOW` permission + Material 3 compliance
3. **Sheet animations:** Use Material Motion specs (standard Android transitions)
4. **Waveform:** Real-time audio visualization (can use third-party lib like Visualizer API)
5. **Dark mode:** Implement via Material 3 theme system (auto-switches with system setting)
6. **Haptics:** Use VibrationEffect API (optional, feature-gate)

---

## **Success Criteria (Design Phase)**

- [ ] All flows clickable in prototype
- [ ] Flows tested with 3-5 users (guerrilla testing)
- [ ] 2-3 sec tap-to-listening, 6-9 sec to text-in-app (feel right)
- [ ] Waveform animation responds smoothly to audio
- [ ] Dark mode looks as good as light
- [ ] All text readable at system font size (large text)

---

**Design Owner:** [Designer name]  
**PM:** Dean Sakihama  
**Status:** Ready for design phase  
**Timeline:** 2-3 weeks for mockups + prototype

