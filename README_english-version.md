# WhatsMicFix v1.3-stable (LSPosed Module)

<img width="512" height="512" alt="WhatsMicFix_Logo" src="https://github.com/user-attachments/assets/2394432f-e0d2-456e-8edd-fbe5f5cbe2e1" />

Enhances the **quality and level** of WhatsApp voice notes when the microphone input is lower than expected.
Allows a **configurable gain in dB** plus optional **pre-boost**, **AGC (Automatic Gain Control)** and **noise suppression** *(AGC/NS improvements were introduced in v1.2)*.

---

## ✨ Status

* Tested on recent devices (e.g., Pixel 8 on Android 16).
* The effect is **clear and noticeable**; v1.3 is **more stable** in how hooks are applied (less randomness than in earlier versions). Force-stopping WhatsApp is **less** critical than in v1.2 (still recommended to guarantee changes).
* Stability still depends on how WhatsApp internally handles `AudioRecord`, but it is **improved over previous versions**.
* Includes **advanced pre-boost with intelligent compression** to avoid distortion at high boosts.

---

## ✨ What’s new in v1.3

* **Extended gain range**: **0.5× to 4.0×** (was 0.5× to 2.5× in v1.2).
* **dB range**: approximately **–6 dB to +12 dB**.
* **Dynamic compression** + **soft limiting** to prevent distortion and smooth peaks.
* **Improved stability**: less need to force-stop WhatsApp.
* **Hot-reload** system: configuration changes are applied automatically.

---

## 🚀 Usage

1. Install the APK as an **LSPosed module** and enable it for:

   * *WhatsApp* (`com.whatsapp`)
   * **Framework** (`system`)
   * **Android System** (`android`)

   > **If you don’t see “system” or “android”** in the scope list: go to **LSPosed Settings → (top-right icon) → Hide** and **uncheck “System apps”** to show them.
2. Open **WhatsMicFix** and adjust preferences:

   * Gain in dB (**–6 dB … +12 dB**)
   * Optional pre-boost
   * AGC and Noise Suppression
   * Force built-in microphone
3. Set the **gain** with the slider (recommended: **1.5× – 3.0×**).
4. **Optional (recommended after changing settings)**: **System Settings → Apps → WhatsApp → Force stop**, then reopen WhatsApp.
5. Open WhatsApp and record a voice note.

   * If you only close it from **Recents**, the process may stay alive and not apply changes.
   * After launching, wait about **5 s** so WhatsApp initializes `AudioRecord` and the hooks can take effect.

> *Note:* v1.3 applies changes automatically, but **force-stopping** WhatsApp guarantees full activation of the new settings.

---

## Advanced Configuration

* **Respect app format**: Keep the audio format requested by the app (recommended).
* **Force MIC source**: Enable only if the microphone source doesn’t switch during recording.
* **AGC/Noise Suppressor**: Additional input-quality improvements.

---

## 📋 Requirements

* Android 12 or higher
* LSPosed
* WhatsApp (stable build recommended)

---

## 📌 Technical Notes

WhatsMicFix is developed following a **Project-Based Learning (PBL)** approach.
The module intercepts and modifies **`AudioRecord`** to improve WhatsApp’s input signal.
Hook persistence still depends on WhatsApp’s internal behavior: some threads may block hooks, while in other cases they apply correctly.
Future work aims to further improve stability and reduce dependency on internal WhatsApp behavior.

**Other marked apps** (potentially compatible, **not the main goal** and **untested**):

```
com.google.android.apps.recorder       # Google Recorder
com.samsung.android.app.memo
com.sec.android.app.voicenote
com.android.soundrecorder              # AOSP
org.telegram.messenger
com.facebook.orca                      # Messenger
us.zoom.videomeetings
com.skype.raider
com.discord
```

Contributions and ideas to further optimize the module are welcome.

---

## ⚙️ Build

```bash
./gradlew clean assembleRelease
```


