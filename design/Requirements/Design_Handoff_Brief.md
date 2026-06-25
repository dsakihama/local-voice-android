# Design Handoff: Local Voice Android App

**From:** Dean Sakihama  
**To:** Design team  
**Date:** 2026-06-16  
**Deliverable:** Interactive prototype (Figma) → iterate → finalize components

---

## **The Ask**

We're building a personal voice-to-text tool for Android (Pixel 10 Pro XL). I want to see it come to life visually—wireframes, interactions, animations—so we can discover what actually works vs. what sounded good on paper.

**This is exploratory.** We're not locked into the spec. If you see a better pattern, a clearer flow, or a smarter way to surface information—that's the whole point of this phase.

---

## **Core Concept (30 seconds)**

User taps FAB → picks intent (AI prompt vs. text message vs. email vs. notes) → dictates → app cleans it up based on context → shows menu of most-used target apps (Claude, Slack, Obsidian, etc.) → text lands in that app.

**Three critical user paths for Android v1:**
1. **Comms flow** — Dictate message → land in Slack/WhatsApp/SMS cleanly (most frequent)
2. **AI flow** — Dictate prompt → land in Claude web interface cleanly
3. **Obsidian flow** — Dictate note → land in Obsidian cleanly

---

## **Key Deliverables**

### **Phase 1: Wireframe Prototype (Week 1)**
- Low-fidelity flows for happy path + fallback (clipboard)
- Clickable prototype in Figma
- Ready for me to test the UX (does this make sense? is it fast? annoying?)

**Questions for you:**
- Is the bottom sheet the right container for intent selection? Or should it be a quick pill-style selector at the top?
- Should the target app menu show frequency counts (motivation), or is that clutter?
- How do we communicate "processing" clearly (spinner + text? just animation?)
- Is FAB the right always-on surface, or something else?

### **Phase 2: High-Fidelity Mockups (Week 2)**
- After we iterate on wireframes
- Real colors, typography, animations
- Component library (FAB, sheets, buttons, toasts)
- Dark mode variants

### **Phase 3: Animation Specs (Week 2-3)**
- Waveform animation (responds to audio level in real-time)
- State transitions (listening → processing → success)
- Gesture interactions (swipe to dismiss, tap targets)

---

## **Design Constraints & Freedoms**

### **Locked In**
- FAB as primary trigger (system overlay, always visible, draggable)
- Bottom sheet for menus (intent + target app selection)
- Waveform visualization during listening
- Three-step flow: intent → dictate → target app
- Material Design 3 system

### **Open to Explore**
- Color palette (suggest something but I'm flexible)
- Icon style (simple, app-native, or custom?)
- Typography hierarchy (how much text should be visible at once?)
- Animation smoothness (subtle or delightful?)
- Widget designs (home screen, lock screen—feel free to propose better layouts)

---

## **Use Cases to Design For**

**Primary (Android v1):**
1. Quick comms (Slack, WhatsApp, SMS) — 30 sec, "Text" intent, frequent
2. Claude prompts — 1-2 min, "AI Prompt" intent, thinking work
3. Obsidian notes — 1-2 min, "Notes" intent, capture ideas

**Secondary (don't over-optimize):**
1. Email drafts (Gmail)

**Edge cases to consider:**
- Mic access denied (show permission screen)
- Processing fails (graceful error + retry)
- Accessibility Service blocked (fallback to clipboard + notification)
- User wants to paste to an app that's not in the menu (add ability to browse all apps?)

---

## **Critical Interactions**

### **Listening State** (Most important visual feedback)
When user is actively recording, they need to feel confident audio is capturing. Current idea: red dot + animated waveform. But maybe there's a better pattern?

**Questions:**
- Should we show decibel level or just bars moving?
- Should the waveform be colorful or minimal?
- Should there be haptic feedback + visual? Or is that redundant?
- Is 2 seconds of silence auto-stop obvious, or do we need a clearer signal?

### **Processing State** 
User just stopped speaking. Text is being cleaned. They need to know it's happening and roughly how long.

**Current idea:** Spinner + "Processing... 4-9 seconds"

**Questions:**
- Should we show the raw transcription → cleaning live? Or hide the sausage-making?
- Would a progress bar (even fake/indeterminate) feel better than a spinner?

### **Target App Menu**
User needs to pick where to paste. We're showing most-used first (learned from their behavior). But should the menu feel "smart" or "simple"?

**Current idea:** Grouped (AI / Comms / PKB), sorted by frequency, with usage counts

**Questions:**
- Is usage count motivational or visually noisy?
- Should the most-used app be highlighted / default / pre-selected?
- Should there be a "paste directly next time" option to skip this menu?
- Should the menu show app icons, just text, or both?

---

## **Success Metrics for Design Phase**

- [ ] Interactive prototype—I can tap through the full happy path in <1 min
- [ ] Waveform animation feels responsive (not janky, not laggy)
- [ ] Intent selector feels natural (not forcing me to overthink)
- [ ] Target app menu scans quickly (I can pick where to paste in <2 sec)
- [ ] Dark mode looks as polished as light
- [ ] Components are documented for engineering (what are the variants?)
- [ ] You've identified at least 2-3 UX questions or edge cases I missed

---

## **Iteration Loop**

**Week 1:** You send wireframe prototype → I use it, poke holes, send feedback  
**Week 2:** You iterate based on feedback → high-fi mockups → animations  
**Week 3:** Components finalized + specs ready for engineering  

**Async feedback:** Figma comments are perfect. Highlight specific issues, don't wait for meetings.

---

## **Questions for Me (Before You Start)**

1. **Widget priority?** Home screen widget is v1.5, lock screen widget is v2. Should I include both in prototype or focus on FAB + sheets first?
   - *My take: Focus on FAB + flows for v1, wireframe widgets separately.*

2. **Accessibility upfront?** Should we design with high contrast, large text, screen reader support from day 1, or iterate that in?
   - *My take: Design for normal case first, then audit for a11y once high-fi.*

3. **App icon?** Should I brief what the app icon should look like, or that's your domain?
   - *My take: Your domain. Microphone is obvious starting point.*

4. **Responsive variants?** Pixel 10 Pro XL is large (6.8"). Should we design for smaller phones too, or optimize for this device?
   - *My take: Optimize for Pixel 10 Pro, adapt down later.*

---

## **Reference Docs**

- **Full design brief:** `Design_Brief_Local_Voice_Android.md` (all the details)
- **Technical spec:** `Local_Voice_Android_App_Spec.md` (architecture, data flow, constraints)
- **Example flows:** See Appendix in design brief

---

## **Tone & Philosophy**

- **Minimal, system-native.** Not trying to win design awards. This should feel like part of Android, not a showy app.
- **Privacy-forward.** Nothing visible should hint at recording content. No previews, no saved conversations in the UI.
- **Fast interactions.** Every tap should feel instant (even if processing happens later).
- **Personal, not generic.** It learns my apps, my voice, my style. UI should hint at that personalization (frequency counts, recent targets).

---

## **Timeline & Next Steps**

1. **This week:** You send wireframe prototype (Figma link)
2. **Next week:** I iterate, send feedback. You polish high-fi mockups.
3. **Week 3:** Component specs ready for engineering (colors, spacing, type scale, animations)

---

**Questions?** Slack me or comment in Figma.

**Ready to start?** Figma project is ready whenever you are.

---

*This is exploratory work. Expect to throw things away and rebuild. That's the point.*
