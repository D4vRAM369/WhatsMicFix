# WhatsMicFix (LSPosed Module)

<img width="512" height="512" alt="WhatsMicFix_1 2" src="https://github.com/user-attachments/assets/2394432f-e0d2-456e-8edd-fbe5f5cbe2e1" />


Mejora la **calidad y el nivel** del audio enviado por WhatsApp cuando el micrófono queda por debajo de lo esperado. Incluye un **pre-boost x2.00 (+6.0 dB)** opcional.

## Estado
Probado en **Pixel 8 (Android 16)**: al activar pre-boost x2.00 y **forzar detención de WhatsApp** y abrirlo de nuevo, la mejora es **perceptible y notable**.  
Al desactivarlo y forzar detención, vuelve al nivel por defecto.

## Uso
1. Instala el APK como **módulo LSPosed** y habilítalo para **WhatsApp**.
2. Abre **WhatsMicFix** y activa **Pre-Boost x2.00 (+6 dB)**.
3. **Ajustes del sistema → Apps → WhatsApp → Forzar detención**.
4. Abre WhatsApp y graba un audio.

> *Nota:* Forzar detención reinicia `AudioRecord` con los nuevos parámetros.

## Requisitos
- Android 12 o superior  
  (probado en Android 16 — Pixel 8)
- LSPosed
- WhatsApp estable

## Compilación
```bash
./gradlew clean assembleRelease
