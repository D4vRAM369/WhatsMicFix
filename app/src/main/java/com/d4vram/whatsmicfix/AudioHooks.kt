package com.d4vram.whatsmicfix

import android.media.*
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object AudioHooks {

    private const val FORCE_SOURCE = MediaRecorder.AudioSource.MIC
    private const val SR_FALLBACK = 44100
    private const val SR_HIGH = 48000
    private const val CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookAudioRecordConstructors()
        hookAudioRecordBuilder(lpparam)
        hookStartRecordingEnableEffects()
        hookMediaRecorderSetSource()
    }

    /** 1) Constructor clásico AudioRecord(int,int,int,int,int) */
    private fun hookAudioRecordConstructors() {
        try {
            XposedHelpers.findAndHookConstructor(
                AudioRecord::class.java,
                Int::class.javaPrimitiveType,  // audioSource
                Int::class.javaPrimitiveType,  // sampleRateInHz
                Int::class.javaPrimitiveType,  // channelConfig
                Int::class.javaPrimitiveType,  // audioFormat
                Int::class.javaPrimitiveType,  // bufferSizeInBytes
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val os = param.args[0] as Int
                        val sr = param.args[1] as Int
                        val ch = param.args[2] as Int
                        val fm = param.args[3] as Int

                        param.args[0] = FORCE_SOURCE
                        param.args[1] = chooseSampleRate(sr)
                        param.args[2] = CHANNEL_CFG
                        param.args[3] = ENCODING

                        Logx.d("AudioRecord(..) mod src:$os→${param.args[0]} sr:$sr→${param.args[1]} ch:$ch→${param.args[2]} fmt:$fm→${param.args[3]}")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        trySetPreferredBuiltInMic(param.thisObject as AudioRecord)
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("Hook constructor AudioRecord falló", t)
        }
    }

    /** 2) Builder moderno AudioRecord.Builder (Android 9+) */
    private fun hookAudioRecordBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClass("android.media.AudioRecord\$Builder", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(cls, "setAudioSource", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val orig = param.args[0] as Int
                        param.args[0] = FORCE_SOURCE
                        Logx.d("Builder.setAudioSource: $orig → ${param.args[0]}")
                    }
                })

            XposedHelpers.findAndHookMethod(cls, "setAudioFormat", AudioFormat::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fmt = (param.args[0] as AudioFormat?) ?: return
                        val newFmt = AudioFormat.Builder()
                            .setSampleRate(chooseSampleRate(fmt.sampleRate))
                            .setChannelMask(CHANNEL_CFG)
                            .setEncoding(ENCODING)
                            .build()
                        param.args[0] = newFmt
                        Logx.d("Builder.setAudioFormat mod aplicado")
                    }
                })

            XposedHelpers.findAndHookMethod(cls, "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.result as? AudioRecord)?.let { trySetPreferredBuiltInMic(it) }
                        Logx.d("Builder.build: preferred BUILTIN_MIC si posible")
                    }
                })

        } catch (t: Throwable) {
            Logx.e("Hook AudioRecord.Builder falló", t)
        }
    }

    /** 3) startRecording(): habilitar AGC y NoiseSuppressor si existen */
    private fun hookStartRecordingEnableEffects() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java,
                "startRecording",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rec = param.thisObject as AudioRecord
                        val sid = rec.audioSessionId
                        try {
                            if (AutomaticGainControl.isAvailable()) {
                                AutomaticGainControl.create(sid)?.enabled = true
                                Logx.d("AGC habilitado sid=$sid")
                            } else Logx.d("AGC no disponible")
                        } catch (t: Throwable) {
                            Logx.e("AGC error", t)
                        }

                        try {
                            if (NoiseSuppressor.isAvailable()) {
                                NoiseSuppressor.create(sid)?.enabled = true
                                Logx.d("NS habilitado sid=$sid")
                            } else Logx.d("NS no disponible")
                        } catch (t: Throwable) {
                            Logx.e("NS error", t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("Hook startRecording() falló", t)
        }
    }

    /** 4) MediaRecorder: forzar setAudioSource() → MIC por si WhatsApp lo usa */
    private fun hookMediaRecorderSetSource() {
        try {
            XposedHelpers.findAndHookMethod(
                MediaRecorder::class.java,
                "setAudioSource",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[0] as Int
                        param.args[0] = FORCE_SOURCE
                        Logx.d("MediaRecorder.setAudioSource: $original → ${param.args[0]}")
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("Hook MediaRecorder.setAudioSource() falló", t)
        }
    }

    // ===== Helpers =====

    private fun chooseSampleRate(requested: Int): Int {
        return when {
            requested >= SR_HIGH -> requested
            Build.VERSION.SDK_INT >= 30 -> SR_HIGH
            else -> SR_FALLBACK
        }
    }

    private fun trySetPreferredBuiltInMic(rec: AudioRecord) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val routed = AudioRecord::class.java.getMethod("getRoutedDevice").invoke(rec) as? AudioDeviceInfo
                Logx.d("Routed device: ${routed?.productName} type=${routed?.type}")

                val setPref = AudioRecord::class.java.getMethod("setPreferredDevice", AudioDeviceInfo::class.java)
                // availableAudioDevices es público en 12L+; en otros cae en excepción y seguimos.
                val devices = try { rec.availableAudioDevices } catch (_: Throwable) { null }
                val builtin = devices?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                if (builtin != null) {
                    setPref.invoke(rec, builtin)
                    Logx.d("Preferred device → BUILTIN_MIC")
                }
            } catch (_: Throwable) {
                Logx.d("No se pudo fijar preferred device (no crítico)")
            }
        }
    }
}
