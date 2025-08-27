package com.d4vram.whatsmicfix

import android.content.Context
import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object AudioHooks {

    private const val SR_FALLBACK = 44100
    private const val SR_HIGH = 48000

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Cargar prefs al entrar en el proceso de WhatsApp
        Prefs.reload()

        hookAudioRecordConstructors(lpparam)
        hookAudioRecordBuilder(lpparam)
        hookStartRecordingEnableEffects()
        hookMediaRecorderSetSource()
        hookAudioRecordReadOverloads()
    }

    // === 1) Constructor clásico: AudioRecord(int,int,int,int,int) ===
    @Suppress("UNUSED_PARAMETER")
    private fun hookAudioRecordConstructors(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookConstructor(
                AudioRecord::class.java,
                Int::class.javaPrimitiveType, // audioSource
                Int::class.javaPrimitiveType, // sampleRate
                Int::class.javaPrimitiveType, // channelConfig
                Int::class.javaPrimitiveType, // audioFormat
                Int::class.javaPrimitiveType, // bufferSize
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        // Decisiones “suaves” según prefs
                        val origSrc = p.args[0] as Int
                        val origSr  = p.args[1] as Int
                        val origCh  = p.args[2] as Int
                        val origFmt = p.args[3] as Int

                        if (Prefs.forceSourceMic) {
                            p.args[0] = MediaRecorder.AudioSource.MIC
                        }

                        if (!Prefs.respectAppFormat) {
                            // Sólo retocamos sample rate si es 0/extraño o si podemos subir a 48k en Android 11+
                            p.args[1] = chooseSampleRate(origSr)
                            // Mantén formato PCM16 y MONO para pre-boost estable
                            p.args[2] = AudioFormat.CHANNEL_IN_MONO
                            p.args[3] = AudioFormat.ENCODING_PCM_16BIT
                        } else {
                            // Respetar formato, pero si SR inválido usa fallback
                            if (origSr <= 0) p.args[1] = SR_FALLBACK
                        }

                        Logx.d(
                            "AudioRecord(..) src:$origSrc→${p.args[0]} sr:$origSr→${p.args[1]} ch:$origCh→${p.args[2]} fmt:$origFmt→${p.args[3]}"
                        )
                    }
                    override fun afterHookedMethod(p: MethodHookParam) {
                        trySetPreferredBuiltInMic(p.thisObject as AudioRecord)
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("Hook constructor AudioRecord falló", t)
        }
    }

    // === 2) Builder moderno (Android 9+) ===
    private fun hookAudioRecordBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = XposedHelpers.findClass("android.media.AudioRecord\$Builder", lpparam.classLoader)

            // setAudioSource(int)
            XposedHelpers.findAndHookMethod(cls, "setAudioSource", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val orig = p.args[0] as Int
                        if (Prefs.forceSourceMic) {
                            p.args[0] = MediaRecorder.AudioSource.MIC
                            Logx.d("Builder.setAudioSource: $orig → ${p.args[0]}")
                        } else {
                            Logx.d("Builder.setAudioSource (respetado): $orig")
                        }
                    }
                })

            // setAudioFormat(AudioFormat)
            XposedHelpers.findAndHookMethod(cls, "setAudioFormat", AudioFormat::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val fmtIn = (p.args[0] as AudioFormat?) ?: return
                        if (!Prefs.respectAppFormat) {
                            val newFmt = AudioFormat.Builder()
                                .setSampleRate(chooseSampleRate(fmtIn.sampleRate))
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                            p.args[0] = newFmt
                            Logx.d("Builder.setAudioFormat: forzado PCM16/mono/SR optimizado")
                        } else {
                            // Respetar: sólo asegurar SR válido
                            val sr = if (fmtIn.sampleRate <= 0) SR_FALLBACK else fmtIn.sampleRate
                            // Validate encoding to avoid ENCODING_INVALID
                            val encoding = if (fmtIn.encoding == AudioFormat.ENCODING_INVALID) {
                                AudioFormat.ENCODING_PCM_16BIT
                            } else {
                                fmtIn.encoding
                            }
                            
                            if (sr != fmtIn.sampleRate || encoding != fmtIn.encoding) {
                                val newFmt = AudioFormat.Builder()
                                    .setSampleRate(sr)
                                    .setChannelMask(fmtIn.channelMask)
                                    .setEncoding(encoding)
                                    .build()
                                p.args[0] = newFmt
                                Logx.d("Builder.setAudioFormat: SR ajustado a $sr y/o encoding validado (resto respetado)")
                            } else {
                                Logx.d("Builder.setAudioFormat: respetado")
                            }
                        }
                    }
                })

            // build()
            XposedHelpers.findAndHookMethod(cls, "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        (p.result as? AudioRecord)?.let { trySetPreferredBuiltInMic(it) }
                        Logx.d("Builder.build: preferred BUILTIN_MIC si posible")
                    }
                })

        } catch (t: Throwable) {
            Logx.e("Hook AudioRecord.Builder falló", t)
        }
    }

    // === 3) startRecording(): AGC/NS opcionales y recarga de prefs ===
    private fun hookStartRecordingEnableEffects() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "startRecording",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val rec = p.thisObject as AudioRecord
                        val sid = rec.audioSessionId

                        // Prefs frescas, pero con anti-spam E/S
                        Prefs.reloadIfStale(500)

                        if (Prefs.enableAgc) {
                            try {
                                if (AutomaticGainControl.isAvailable()) {
                                    AutomaticGainControl.create(sid)?.enabled = true
                                    Logx.d("AGC habilitado sid=$sid")
                                } else Logx.d("AGC no disponible")
                            } catch (t: Throwable) { Logx.e("AGC error", t) }
                        } else {
                            Logx.d("AGC: desactivado por prefs")
                        }

                        if (Prefs.enableNs) {
                            try {
                                if (NoiseSuppressor.isAvailable()) {
                                    NoiseSuppressor.create(sid)?.enabled = true
                                    Logx.d("NS habilitado sid=$sid")
                                } else Logx.d("NS no disponible")
                            } catch (t: Throwable) { Logx.e("NS error", t) }
                        } else {
                            Logx.d("NS: desactivado por prefs")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("Hook startRecording() falló", t)
        }
    }

    // === 4) MediaRecorder.setAudioSource(int) ===
    private fun hookMediaRecorderSetSource() {
        try {
            XposedHelpers.findAndHookMethod(
                MediaRecorder::class.java, "setAudioSource", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val original = p.args[0] as Int
                        if (Prefs.forceSourceMic) {
                            p.args[0] = MediaRecorder.AudioSource.MIC
                            Logx.d("MediaRecorder.setAudioSource: $original → ${p.args[0]}")
                        } else {
                            Logx.d("MediaRecorder.setAudioSource (respetado): $original")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("Hook MediaRecorder.setAudioSource() falló", t)
        }
    }

    // === 5) Pre-boost en todos los overloads razonables de read(...) ===
    private fun hookAudioRecordReadOverloads() {
        // short[] read(short[], off, size)
        tryHookReadShort3()
        // short[] read(short[], off, size, readMode)
        tryHookReadShort4()

        // byte[] read(byte[], off, size)
        tryHookReadByte3()
        // byte[] read(byte[], off, size, readMode)
        tryHookReadByte4()

        // ByteBuffer read(ByteBuffer, sizeInBytes)
        tryHookReadBB2()
        // ByteBuffer read(ByteBuffer, sizeInBytes, readMode) (en algunos builds)
        tryHookReadBB3()
    }

    private fun tryHookReadShort3() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val ret = p.result as Int
                        if (ret <= 0) return
                        Prefs.reloadIfStale(500)
                        if (!Prefs.enablePreboost) return

                        val factor = Prefs.boostFactor
                        if (factor < 0.999f || factor > 1.001f) {
                            val buf = p.args[0] as ShortArray
                            val off = p.args[1] as Int
                            val req = p.args[2] as Int
                            val size = min(ret, req)
                            boostShortArrayQ10(buf, off, size, factor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.e("Hook read(short[]) 3-args falló", t) }
    }

    private fun tryHookReadShort4() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val ret = p.result as Int
                        if (ret <= 0) return
                        Prefs.reloadIfStale(500)
                        if (!Prefs.enablePreboost) return

                        val factor = Prefs.boostFactor
                        if (factor < 0.999f || factor > 1.001f) {
                            val buf = p.args[0] as ShortArray
                            val off = p.args[1] as Int
                            val req = p.args[2] as Int
                            val size = min(ret, req)
                            boostShortArrayQ10(buf, off, size, factor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.d("read(short[],off,size,mode) no existe en este framework (ok)") }
    }

    private fun tryHookReadByte3() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val ret = p.result as Int
                        if (ret <= 0) return
                        Prefs.reloadIfStale(500)
                        if (!Prefs.enablePreboost) return

                        val factor = Prefs.boostFactor
                        if (factor < 0.999f || factor > 1.001f) {
                            val buf = p.args[0] as ByteArray
                            val off = p.args[1] as Int
                            val req = p.args[2] as Int
                            val size = min(ret, req)
                            boostByteArrayPCM16Q10(buf, off, size, factor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.e("Hook read(byte[]) 3-args falló", t) }
    }

    private fun tryHookReadByte4() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val ret = p.result as Int
                        if (ret <= 0) return
                        Prefs.reloadIfStale(500)
                        if (!Prefs.enablePreboost) return

                        val factor = Prefs.boostFactor
                        if (factor < 0.999f || factor > 1.001f) {
                            val buf = p.args[0] as ByteArray
                            val off = p.args[1] as Int
                            val req = p.args[2] as Int
                            val size = min(ret, req)
                            boostByteArrayPCM16Q10(buf, off, size, factor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.d("read(byte[],off,size,mode) no existe en este framework (ok)") }
    }

    private fun tryHookReadBB2() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ByteBuffer::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val ret = p.result as Int
                        if (ret <= 0) return
                        Prefs.reloadIfStale(500)
                        if (!Prefs.enablePreboost) return

                        val factor = Prefs.boostFactor
                        if (factor < 0.999f || factor > 1.001f) {
                            val bb = p.args[0] as ByteBuffer
                            boostByteBufferPCM16Q10(bb, ret, factor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.d("read(ByteBuffer,size) no existe en este framework (ok)") }
    }

    private fun tryHookReadBB3() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val ret = p.result as Int
                        if (ret <= 0) return
                        Prefs.reloadIfStale(500)
                        if (!Prefs.enablePreboost) return

                        val factor = Prefs.boostFactor
                        if (factor < 0.999f || factor > 1.001f) {
                            val bb = p.args[0] as ByteBuffer
                            boostByteBufferPCM16Q10(bb, ret, factor)
                        }
                    }
                }
            )
        } catch (t: Throwable) { Logx.d("read(ByteBuffer,size,mode) no existe en este framework (ok)") }
    }

    // ==== Helpers ====

    private fun chooseSampleRate(requested: Int): Int =
        when {
            requested > 0 && requested >= SR_HIGH -> requested
            Build.VERSION.SDK_INT >= 30 -> SR_HIGH
            requested > 0               -> requested
            else                        -> SR_FALLBACK
        }

    private fun trySetPreferredBuiltInMic(rec: AudioRecord) {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            val setPref = AudioRecord::class.java.getMethod(
                "setPreferredDevice",
                AudioDeviceInfo::class.java
            )
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

    // Boost en Q10 (factor * 1024) para ahorrar CPU
    private fun boostShortArrayQ10(buf: ShortArray, off: Int, size: Int, factor: Float) {
        val q = (factor * 1024f + 0.5f).toInt()
        var i = off
        val end = off + size
        while (i < end) {
            val boosted = (buf[i].toInt() * q) shr 10
            buf[i] = clamp16(boosted)
            i++
        }
    }

    private fun boostByteArrayPCM16Q10(bytes: ByteArray, off: Int, size: Int, factor: Float) {
        val q = (factor * 1024f + 0.5f).toInt()
        var i = off
        val end = off + size - 1
        while (i < end) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val boosted = (sample * q) shr 10
            val clamped = clamp16(boosted).toInt()
            bytes[i]     = (clamped and 0xFF).toByte()
            bytes[i + 1] = ((clamped shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun boostByteBufferPCM16Q10(bb: ByteBuffer, validBytes: Int, factor: Float) {
        val savedOrder = bb.order()
        bb.order(ByteOrder.LITTLE_ENDIAN)
        val q = (factor * 1024f + 0.5f).toInt()
        val pos = bb.position()
        val lim = pos + validBytes
        var i = pos
        while (i + 1 < lim) {
            val s = bb.getShort(i).toInt()
            val boosted = (s * q) shr 10
            bb.putShort(i, clamp16(boosted))
            i += 2
        }
        bb.order(savedOrder)
    }
}
