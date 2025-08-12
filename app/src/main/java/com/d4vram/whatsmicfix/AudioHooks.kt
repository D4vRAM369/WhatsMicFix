package com.d4vram.whatsmicfix

import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.AudioManager
import android.content.Context
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object AudioHooks {

    private const val FORCE_SOURCE = MediaRecorder.AudioSource.MIC
    private const val SR_FALLBACK = 44100
    private const val SR_HIGH = 48000
    private const val CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Cargar prefs al iniciar en el proceso de WhatsApp
        Prefs.reload()

        hookAudioRecordConstructors()
        hookAudioRecordBuilder(lpparam)
        hookStartRecordingEnableEffects()
        hookMediaRecorderSetSource()
        hookAudioRecordReadOverloads() // aplica boost si está activo
    }

    /** 1) Constructor clásico AudioRecord(int,int,int,int,int) */
    private fun hookAudioRecordConstructors() {
        try {
            XposedHelpers.findAndHookConstructor(
                AudioRecord::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
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

    /** 3) startRecording(): habilitar AGC/NS y recargar prefs por si la UI cambió */
    private fun hookStartRecordingEnableEffects() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "startRecording",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rec = param.thisObject as AudioRecord
                        val sid = rec.audioSessionId

                        // Recargar prefs cada vez que se inicia una grabación
                        Prefs.reload()

                        try {
                            if (AutomaticGainControl.isAvailable()) {
                                AutomaticGainControl.create(sid)?.enabled = true
                                Logx.d("AGC habilitado sid=$sid")
                            } else Logx.d("AGC no disponible")
                        } catch (t: Throwable) { Logx.e("AGC error", t) }

                        try {
                            if (NoiseSuppressor.isAvailable()) {
                                NoiseSuppressor.create(sid)?.enabled = true
                                Logx.d("NS habilitado sid=$sid")
                            } else Logx.d("NS no disponible")
                        } catch (t: Throwable) { Logx.e("NS error", t) }
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
                MediaRecorder::class.java, "setAudioSource", Int::class.javaPrimitiveType,
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

    /** 5) Pre‑boost PCM con clip‑guard en los overloads de read(...) */
    private fun hookAudioRecordReadOverloads() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ret = param.result as Int
                        if (ret > 0 && Prefs.enablePreboost) {
                            val buf = param.args[0] as ShortArray
                            val off = param.args[1] as Int
                            val size = min(ret, param.args[2] as Int)
                            boostShortArray(buf, off, size, Prefs.boostFactor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.e("Hook read(short[]) falló", t) }

        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ret = param.result as Int
                        if (ret > 0 && Prefs.enablePreboost) {
                            val buf = param.args[0] as ByteArray
                            val off = param.args[1] as Int
                            val size = min(ret, param.args[2] as Int)
                            boostByteArrayPCM16(buf, off, size, Prefs.boostFactor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.e("Hook read(byte[]) falló", t) }

        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ret = param.result as Int
                        if (ret > 0 && Prefs.enablePreboost) {
                            val bb = param.args[0] as ByteBuffer
                            boostByteBufferPCM16(bb, ret, Prefs.boostFactor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.e("Hook read(ByteBuffer) falló", t) }
    }

    // ===== Helpers =====

    private fun chooseSampleRate(requested: Int): Int =
        when {
            requested >= SR_HIGH -> requested
            Build.VERSION.SDK_INT >= 30 -> SR_HIGH
            else -> SR_FALLBACK
        }

    private fun trySetPreferredBuiltInMic(rec: AudioRecord) {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            // setPreferredDevice(AudioDeviceInfo) por reflexión
            val setPref = AudioRecord::class.java.getMethod(
                "setPreferredDevice",
                AudioDeviceInfo::class.java
            )

            // Obtener un Context del proceso actual (ActivityThread)
            val app = try {
                val at = Class.forName("android.app.ActivityThread")
                at.getMethod("currentApplication").invoke(null) as? android.app.Application
            } catch (_: Throwable) { null }

            val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val inputs = am?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()

            val builtin = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtin != null) {
                setPref.invoke(rec, builtin)
                Logx.d("Preferred device → BUILTIN_MIC")
            } else {
                Logx.d("No se encontró BUILTIN_MIC en getDevices()")
            }
        } catch (_: Throwable) {
            Logx.d("No se pudo fijar preferred device (no crítico)")
        }
    }


    private fun clamp16(v: Int): Short = max(-32768, min(32767, v)).toShort()

    private fun boostShortArray(buf: ShortArray, off: Int, size: Int, factor: Float) {
        for (i in off until off + size) {
            val v = (buf[i] * factor).roundToInt()
            buf[i] = clamp16(v)
        }
    }

    private fun boostByteArrayPCM16(bytes: ByteArray, off: Int, size: Int, factor: Float) {
        var i = off
        val end = off + size - 1
        while (i < end) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val boosted = (sample * factor).roundToInt()
            val clamped = clamp16(boosted).toInt()
            bytes[i]     = (clamped and 0xFF).toByte()
            bytes[i + 1] = ((clamped shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun boostByteBufferPCM16(bb: ByteBuffer, validBytes: Int, factor: Float) {
        val order = bb.order()
        bb.order(ByteOrder.LITTLE_ENDIAN)
        val pos = bb.position()
        val lim = pos + validBytes
        var i = pos
        while (i + 1 < lim) {
            val sample = bb.getShort(i)
            val boosted = (sample * factor).roundToInt()
            bb.putShort(i, clamp16(boosted))
            i += 2
        }
        bb.order(order)
    }
}
