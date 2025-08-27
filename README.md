# WhatsMicFix (LSPosed Module)

<img width="512" height="512" alt="WhatsMicFix_Logo" src="https://github.com/user-attachments/assets/2394432f-e0d2-456e-8edd-fbe5f5cbe2e1" />

Mejora la **calidad y el nivel** del audio enviado por WhatsApp cuando el micrófono queda por debajo de lo esperado.  
Permite aplicar una **ganancia configurable en dB** junto a funciones opcionales como **pre-boost**, **AGC (Control Automático de Ganancia)** y **supresión de ruido** _(AGC y NS mejoras de la versión 1.2)_.

---

## ✨ Estado
- Probado en dispositivos recientes (ej. Pixel 8 con Android 16).  
- El efecto es **perceptible y notable** tras reiniciar WhatsApp con los parámetros ajustados.  
- La estabilidad puede variar dependiendo de cómo WhatsApp maneje internamente `AudioRecord`.

---

## 🚀 Uso
1. Instala el APK como **módulo LSPosed** y habilítalo para **WhatsApp**.  
2. Abre la app **WhatsMicFix** y ajusta las preferencias de audio:  
   - Ganancia en dB (–6 dB a +8 dB aprox.)  
   - Pre-boost opcional  
   - AGC y supresión de ruido  
   - Forzado de micrófono interno  
3. **Forzar detención de WhatsApp y volver a abrirlo.**

Al forzar la detención se cierra por completo el proceso de WhatsApp. Al abrirlo de nuevo, LSPosed vuelve a cargar el módulo: se leen las preferencias guardadas y se registran los hooks (**`HookEntry.kt`** → **`AudioHooks.kt`**) con la configuración actual de **`Prefs.kt`**.
Si solo cierras WhatsApp desde **Recientes**, el proceso suele quedar vivo y los cambios no se aplican.
Después de abrirlo otra vez, **espera 5 segundos antes de grabar el primer audio** para que WhatsApp inicialice AudioRecord y los hooks actúen correctamente. Desde ese momento, los nuevos parámetros (ganancia, pre-boost, AGC, supresión de ruido, etc.) estarán activos en la grabación.

> **Nota:** Forzar detención reinicia **`AudioRecord`** con los nuevos parámetros aplicados por el módulo.

---

## 📋 Requisitos
- Android 12 o superior  
- LSPosed  
- WhatsApp (versión estable recomendada)  

---

## 📌 Notas técnicas

WhatsMicFix está desarrollado siguiendo un enfoque **Project-Based Learning** **(PBL)**.

El módulo se centra en interceptar y modificar _AudioRecord_ para mejorar la señal de entrada en WhatsApp.

La persistencia de los hooks depende del comportamiento interno de WhatsApp: en algunos escenarios los hooks son bloqueados por subprocesos, en otros se aplican correctamente.

El objetivo futuro es mejorar la estabilidad y reducir la dependencia del comportamiento interno de WhatsApp.

Se agradecen contribuciones o ideas para seguir optimizando el módulo.


## ⚙️ Compilación
```bash
./gradlew clean assembleRelease
