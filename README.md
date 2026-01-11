<p align="center">
  <img src="https://github.com/user-attachments/assets/5d8cfba5-8faa-4176-a689-c47b982c41ee" width="400" height="400" alt="WhatsMicFix icon"/><br><br>
  <b>🎧 WhatsMicFix v1.4</b><br><br>
  <img src="https://img.shields.io/github/downloads/D4vRAM369/WhatsMicFix/total?color=blue" alt="Downloads"/>
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

---

[🌍 Versión completa en inglés (recomendada para SEO y contribuciones globales)](README_english-version.md) | 🇪🇸 Esta es la versión en español principal


## ✨ Nuevo en v1.4

* **Estabilidad total**: el boost y el compresor ahora se preparan *antes* de iniciar la grabación, eliminando completamente la **race condition** que provocaba audios sin efecto.
* **Triple capa de protección**:
  * Detección anticipada del formato (`AudioFormat.ENCODING_PCM_16BIT`) antes del primer `read()`.
  * **Modo permisivo**: si WhatsApp devuelve un formato desconocido, el módulo asume PCM16.
  * Fallback automático para evitar rechazos o errores de inicialización.
* **Reseteo inteligente del compresor** en cada sesión → evita heredar ganancia baja del audio anterior.
* **Splash screen negra** al abrir la app (inicio limpio y sin flicker).
* **Rendimiento mejorado**: menor carga en CPU, inicio más rápido y comportamiento consistente.
* **Nueva sección de soporte en el README** para colaborar y dar estrellas ⭐.

---

## 🚀 Uso

1. Instala el APK como **módulo LSPosed** y habilítalo para:

   * *WhatsApp* (`com.whatsapp`)
   * **Framework del sistema** (`system`)
   * **Sistema Android** (`android`)

   > **Si no ves “system” o “android”** en la lista de scopes: ve a **Ajustes de LSPosed → (icono arriba a la derecha) → Ocultar** y **desmarca “Aplicaciones del sistema”**.

2. Abre **WhatsMicFix** y ajusta las preferencias:

   * Ganancia en dB (**–6 dB … +12 dB**, hasta ×4.0)
   * Pre-boost opcional
   * AGC y Supresión de ruido
   * Forzado de micrófono interno

3. Ajusta la **ganancia** con el deslizador (recomendado: **1.5× – 3.0×**).

4. (Opcional) **Forzar detención** de WhatsApp tras cambiar ajustes:

   * **Ajustes del sistema → Apps → WhatsApp → Forzar detención**, luego vuelve a abrir WhatsApp.

5. Abre WhatsApp y graba un audio.

   * Si solo cierras desde **Recientes**, el proceso puede quedar activo y no aplicar cambios.
   * Espera unos **5 segundos** para que WhatsApp inicialice `AudioRecord` y los hooks actúen correctamente.

> *Nota:* La v1.4 aplica cambios en caliente, pero **forzar detención** garantiza la activación completa de los nuevos parámetros.

---

## ⚙️ Configuración Avanzada

* **Respetar formato**: mantiene el formato de audio solicitado por la app (recomendado).
* **Forzar fuente MIC**: úsalo solo si el micrófono no cambia correctamente.
* **AGC / Supresor de ruido**: mejora adicional de calidad de entrada.
* **Logs de depuración**: visibles con `adb logcat | grep WhatsMicFix`.

---

## 📊 Mejoras Técnicas

* `updateGlobalBoostFactor()` movido a **beforeHookedMethod()** → el boost se aplica antes de grabar.
* **Detección anticipada** del formato PCM16 y cacheado thread-safe con `ConcurrentHashMap`.
* **Reseteo del compresor** en cada sesión: evita estados de ganancia residuales.
* **Modo permisivo en ensurePcm16()** → procesa incluso si WhatsApp retrasa el formato.
* **Validación completa del flujo de audio** para máxima compatibilidad.

---

## 🛠️ Correcciones

* Eliminado el bug del **primer audio sin boost**.
* Corregida la detección de formato inválido (`AudioFormat.ENCODING_INVALID`).
* Evitada la herencia de valores antiguos del compresor.
* Sin falsos negativos ni rechazos del hook.

---

## 🔹 Comparativa Técnica

| Aspecto                        | v1.3                     | v1.4 (actual) |
|--------------------------------|--------------------------|----------------|
| **Primer audio estable**       | ~90 %                   | ✅ 100 % |
| **Audios consecutivos OK**     | ~95 %                   | ✅ 100 % |
| **Alertas “formato inválido”** | 1–2 por sesión           | 🚫 0 |
| **Tiempo de hook**             | Variable                 | ⚡ Consistente |
| **Logs de diagnóstico**        | Limitados                | 🧠 Completos |

---

## 📚 Notas Técnicas

WhatsMicFix está desarrollado siguiendo el método **Project-Based Learning (PBL)**, que combina aprendizaje práctico con desarrollo real.  
El módulo intercepta y modifica **`AudioRecord`** para mejorar la señal de entrada en WhatsApp, especialmente en **dispositivos Pixel**, donde el volumen del micrófono suele ser bajo.

Esta versión v1.4 marca el paso de un fix experimental a un **módulo de audio profesional**, con mejoras de estabilidad, compatibilidad y eficiencia.

---

## 💬 Soporte al Proyecto

Si este módulo te ha sido útil, considera apoyarlo:

<p align="center">
  <a href="https://github.com/D4vRAM369/WhatsMicFix/stargazers">
    <img src="https://img.shields.io/badge/Give_a_Star_on_GitHub-⭐-yellow?style=for-the-badge"/>
  </a>
  <a href="https://www.buymeacoffee.com/D4vRAM369">
    <img src="https://img.shields.io/badge/Buy_me_a_coffee-☕-blueviolet?style=for-the-badge"/>
  </a>
</p>

---

💡 *Desarrollado por D4vRAM mediante aprendizaje PBL e IA colaborativa.*  
💚 Licencia: **GPLv3 – Software libre, código abierto y transparente.**
