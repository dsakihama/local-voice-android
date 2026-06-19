# FAQ — Dean's Reference

## Building and Deploying to Device (Android Studio)

1. **Connect your phone** — USB or wireless (see [Connecting Pixel 10 Pro XL](#connecting-pixel-10-pro-xl-for-debugging) below)

2. **Sync Gradle** — a yellow bar appears at the top: click **Sync Now**. If it doesn't appear: `File → Sync Project with Gradle Files`

3. **Select your device** — in the toolbar, use the device dropdown next to the Run button and select your Pixel 10 Pro XL

4. **Run** — click the green **▶ Run** button (or `Ctrl+R`). This builds, installs, and launches the app in one step — no separate push needed.

**Debugging output:** `View → Tool Windows → Logcat`, filter by tag to watch specific probe output (e.g. `SttProbe`).

**If Gradle sync fails:** dependency version is likely wrong — check the version in `gradle/libs.versions.toml`.  
**If build fails:** import paths in the Kotlin file need adjusting — Android Studio will underline the bad imports.

---

## Connecting Pixel 10 Pro XL for Debugging

### Prerequisites (both methods)

1. Enable Developer Options: **Settings → About phone → tap Build number 7 times**
2. Enable USB debugging: **Settings → System → Developer Options → USB debugging → ON**
3. Confirm `adb` is on your PATH:
   ```bash
   export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
   ```
   Add that line to `~/.zshrc` to make it permanent.

---

### Wired (USB)

1. Plug USB-C cable into Pixel and Mac.
2. On the Pixel, tap **Allow** when the "Allow USB debugging?" dialog appears. Check "Always allow from this computer" to avoid the prompt on future connects.
3. Verify:
   ```bash
   adb devices
   # Expected: <serial>   device
   ```

That's it. Wired is the most reliable method — use it when you need ADB for the first time on a new machine or after a reboot.

---

### Wireless (Wi-Fi)

Wireless debugging is a **two-step process** — pairing alone is not enough to run ADB commands.

**Step 1 — Pair (one-time per computer)**

1. On the Pixel: **Settings → System → Developer Options → Wireless debugging → ON**
2. Tap **Pair device with pairing code**
3. The screen shows a pairing IP:port and a 6-digit code
4. On your Mac, run:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb pair <ip>:<pairing-port>
   # Enter the 6-digit code when prompted
   ```
5. Pixel confirms "Paired" — you only need to do this once per computer.

**Step 2 — Connect (each session)**

After pairing (or after every device reboot / Wi-Fi reconnect):

1. On the Pixel: **Settings → System → Developer Options → Wireless debugging** (the main screen, not the pairing sub-screen)
2. Note the **IP address & port** shown at the top — this is a different port than the pairing port
3. On your Mac, run:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb connect <ip>:<port>
   ```
4. Verify:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb devices
   # Expected: <ip>:<port>   device
   ```

> **Pairing port ≠ connection port.** The sub-screen port is only for `adb pair`. The main Wireless Debugging screen port is what you pass to `adb connect`.

---

### Troubleshooting

| Symptom | Fix |
|---------|-----|
| `command not found: adb` | Add platform-tools to PATH (see Prerequisites) |
| `adb devices` shows `unauthorized` | Tap Allow on the Pixel debugging dialog |
| `adb devices` shows `offline` | Re-run `adb connect <ip>:<port>` |
| Wireless debugging won't enable | Phone and Mac must be on the same Wi-Fi network |
| AICore / ADB commands fail after connecting | Check `adb devices` shows `device` not just `paired` — run `adb connect` if missing |
| Bootloader-related errors (AICore) | Bootloader must be **locked** (`ro.boot.verifiedbootstate = green`) — do not unlock for development on this project |
