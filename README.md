# WhatsMicFix v1.3-stable (LSPosed Module)

<img width="512" height="512" alt="WhatsMicFix_Logo" src="https://github.com/user-attachments/assets/2394432f-e0d2-456e-8edd-fbe5f5cbe2e1" />

Mejora la **calidad y el nivel** del audio enviado por WhatsApp cuando el micrófono queda por debajo de lo esperado.
Permite aplicar una **ganancia configurable en dB** junto a funciones opcionales como **pre-boost**, **AGC (Control Automático de Ganancia)** y **supresión de ruido** *(AGC y NS mejoras de la versión 1.2)*.

---

## ✨ Estado

* Probado en dispositivos recientes (ej. Pixel 8 con Android 16).
* El efecto es **perceptible y notable**; la v1.3 es **más estable** en cuánto a la aplicación de los hooks (y no de forma aleatoria, aplicando pre-boost con diferencia de volumen en ocasiones y en otras no, como venía sucediendo antes según mi experiencia y por lo que me han informado algunos usuarios). Ya no requiere forzar detención tan estrictamente como en la v1.2 (aunque sigue garantizando la aplicación de cambios).
* La estabilidad puede variar según cómo WhatsApp gestione internamente `AudioRecord`, pero **mejora respecto a versiones anteriores**.
* Incluye **pre-boost avanzado con compresión inteligente** para evitar distorsión en boosts altos.

---

## ✨ Nuevo en v1.3

* **Rango extendido** de ganancia: **0.5× a 4.0×** (antes 0.5× a 2.5×).
* **Rango en dB**: **–6 dB a +12 dB** aprox.
* **Compresión dinámica** + **soft limiting** para prevenir distorsión y suavizar picos.
* **Estabilidad mejorada**: menos necesidad de forzar detención de WhatsApp.
* **Sistema de recarga**: los cambios de configuración se aplican automáticamente.

---

## 🚀 Uso

1. Instala el APK como **módulo LSPosed** y habilítalo para:

   * *WhatsApp* (`com.whatsapp`)
   * **Framework del sistema** (`system`)
   * **Sistema Android** (`android`)

   > **Si no ves “system” o “android”** en la lista de scopes: ve a **Ajustes de LSPosed → (icono de la esquina superior derecha) → Ocultar** y **desmarca “Aplicaciones del sistema”** para mostrarlas.
2. Abre **WhatsMicFix** y ajusta las preferencias:

   * Ganancia en dB (**–6 dB … +12 dB**)
   * Pre-boost opcional
   * AGC y Supresión de ruido
   * Forzado de micrófono interno
3. Ajusta la **ganancia** con el deslizador (recomendado: **1.5× – 3.0×**).
4. **Opcional (pero recomendado al cambiar ajustes)**: **Ajustes del sistema → Apps → WhatsApp → Forzar detención** y vuelve a abrir WhatsApp.
5. Abre WhatsApp y graba un audio.

   * Si solo cierras desde **Recientes**, el proceso puede quedar vivo y no aplicar cambios.
   * Tras abrir, espera \~**5 s** para que WhatsApp inicialice `AudioRecord` y los hooks actúen correctamente.

> *Nota:* La v1.3 aplica cambios automáticamente, pero **forzar detención** garantiza la activación completa de los nuevos ajustes.

---

## Configuración Avanzada

* **Respetar formato**: Mantiene el formato de audio solicitado por la app (recomendado).
* **Forzar fuente MIC**: Actívalo solo si el micro no cambia durante la grabación.
* **AGC/Supresor de ruido**: Mejoras adicionales de calidad de entrada.

---

## 📋 Requisitos

* Android 12 o superior
* LSPosed
* WhatsApp (versión estable recomendada)

---

## 📌 Notas técnicas

WhatsMicFix está desarrollado siguiendo un enfoque **Project-Based Learning (PBL)**.
El módulo intercepta y modifica **`AudioRecord`** para mejorar la señal de entrada en WhatsApp.
La persistencia de los hooks depende del comportamiento interno de WhatsApp: a veces ciertos subprocesos pueden bloquearlos, y en otros casos se aplican correctamente.
El objetivo futuro es seguir mejorando la estabilidad y reducir la dependencia del comportamiento interno de WhatsApp.

**Otras apps marcadas** (potencialmente compatibles, pero **no objetivo principal** y **no testadas**):

```
com.google.android.apps.recorder       # Grabadora Google
com.samsung.android.app.memo
com.sec.android.app.voicenote
com.android.soundrecorder              # AOSP
org.telegram.messenger
com.facebook.orca                      # Messenger
us.zoom.videomeetings
com.skype.raider
com.discord
```

Se agradecen contribuciones o ideas para seguir optimizando el módulo.

---

## ⚙️ Compilación

```bash
./gradlew clean assembleRelease
```
