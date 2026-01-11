<p align="center">
  <img src="https://github.com/user-attachments/assets/5d8cfba5-8faa-4176-a689-c47b982c41ee" width="400" height="400" alt="WhatsMicFix icon"/><br><br>
  <b>🎧 WhatsMicFix v1.4</b><br><br>
  <img src="https://img.shields.io/github/stars/D4vRAM369/WhatsMicFix?style=social" alt="Stars"/>
  <img src="https://img.shields.io/github/downloads/D4vRAM369/WhatsMicFix/total?color=blue" alt="Downloads"/>
  <img src="https://img.shields.io/github/release-date/D4vRAM369/WhatsMicFix?color=green" alt="Latest Release"/>
  <img src="https://img.shields.io/badge/Kotlin-1.9%2B-orange"/>
  <img src="https://img.shields.io/badge/Android-12%2B-brightgreen?logo=android"/>
  <img src="https://img.shields.io/badge/LSPosed-1.9%2B-blue?logo=android"/>
  <img src="https://img.shields.io/badge/Gradle-8.2%2B-02303A?logo=gradle"/>
  <img src="https://img.shields.io/badge/License-GPLv3-green"/>
  <img src="https://img.shields.io/badge/Made_with-ChatGPT_&_PBL-8A2BE2?logo=openai&logoColor=white"/>
  <img src="https://img.shields.io/badge/ClaudeCode_&_PBL-powered-4B0082?style=flat-square&logo=anthropic&logoColor=white"/>
  <img src="https://img.shields.io/badge/Optimized_for-Pixel_Devices-lightblue?logo=google"/>
  <img src="https://img.shields.io/badge/Audio_Processing-DSP-orange"/>
  <img src="https://img.shields.io/badge/Made_with-Love_&_Coffee-ff69b4"/>
  <a href="https://www.buymeacoffee.com/D4vRAM369"><img src="https://img.shields.io/badge/Buy_me_a_coffee-☕-5F7FFF"/></a>
</p>

[🌍 Spanish version in main README](README_.md)

# WhatsMicFix – Fix for Low Mic Volume in WhatsApp Voice Notes on Affected Pixels

**LSPosed module** that applies a configurable pre-amplifier (gain boost) to improve microphone input quality in WhatsApp on Pixel devices where voice notes record too quietly.

Tested and proven on **Pixel 8** with Android 16. May help on other affected Pixels (report in Issues if you test on Pixel 9 series or others). Not needed on devices like Pixel 9a where audio is already good natively.

---

## ✨ What's New in v1.4 (Stable Release)
- **Full stability**: Boost & compressor prepared **before** recording starts → eliminates race condition completely.
- **Triple protection layer**:
  - Early format detection (`AudioFormat.ENCODING_PCM_16BIT`) before first `read()`.
  - Permissive mode: Assume PCM16 if unknown format from WhatsApp.
  - Auto-fallback to prevent initialization errors.
- **Smart compressor reset** per session → no inherited low gain from previous audio.
- Black splash screen for clean app launch (no flicker).
- Improved performance: Lower CPU load, faster startup, consistent behavior.

---

## 🚀 Quick Setup & Usage
1. Install APK as **LSPosed module** and enable for:
   - WhatsApp (`com.whatsapp`)
   - System Framework (`system`)
   - Android System (`android`)
   
   > **Tip**: If "system" or "android" not visible → LSPosed Settings → (top-right icon) → Hide → uncheck "Hide system apps".

2. Open WhatsMicFix app and configure:
   - Gain boost (–6 dB to +12 dB, recommended 1.5× – 3.0×)
   - Optional pre-boost, AGC, noise suppression
   - Force internal mic if needed

3. **Optional but recommended**: Force stop WhatsApp after changes (Settings → Apps → WhatsApp → Force stop) → reopen WhatsApp.
4. Record a voice note in WhatsApp (wait ~5 seconds for AudioRecord init).

> Note: v1.4 supports hot changes, but force stop ensures full effect.

---

## ⚙️ Advanced Settings
- Respect requested format (recommended)
- Force MIC source (only if mic switching fails)
- AGC / Noise suppression (extra quality boost)
- Debug logs: `adb logcat | grep WhatsMicFix`

---

## 📊 Technical Comparison (v1.3 vs v1.4)

| Feature                        | v1.3       | v1.4 (Current) |
|--------------------------------|------------|----------------|
| First audio stable             | ~90%       | 100%           |
| Consecutive audios OK          | ~95%       | 100%           |
| "Invalid format" alerts        | 1–2/session| 0              |
| Hook timing                    | Variable   | Consistent     |
| Diagnostic logs                | Limited    | Full           |

---

## 🛠️ Key Technical Improvements
- `updateGlobalBoostFactor()` moved to `beforeHookedMethod()` for pre-recording boost.
- Thread-safe PCM16 detection & caching with `ConcurrentHashMap`.
- Session-based compressor reset.
- Permissive handling in `ensurePcm16()`.

---

## 💬 Support & Contribute
If this helps you, please consider:
<p align="center">
  <a href="https://github.com/D4vRAM369/WhatsMicFix/stargazers"><img src="https://img.shields.io/badge/Give_a_Star-⭐-yellow?style=for-the-badge"/></a>
  <a href="https://www.buymeacoffee.com/D4vRAM369"><img src="https://img.shields.io/badge/Buy_me_a_coffee-☕-blueviolet?style=for-the-badge"/></a>
</p>

Open Issues for feedback, compatibility reports (your Pixel model + Android version), or feature requests!

---

*Developed via Project-Based Learning (PBL) with collaborative AI tools.*  
💚 **License: GPLv3** – Open source and transparent.

¡Gracias por probarlo! Reporta resultados para mejorar compatibilidad. ☕🔊
