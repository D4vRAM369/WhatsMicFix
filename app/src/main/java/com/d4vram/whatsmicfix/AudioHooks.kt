package com.d4vram.whatsmicfix

import android.content.Intent
import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

private const val APP_PKG = "com.d4vram.whatsmicfix"

object AudioHooks {

    private const val SR_FALLBACK = 44100
    private const val SR_HIGH = 48000

    // Procesamiento profesional con compresión dinámica
    private const val COMPRESSION_RATIO = 4.0f      // 4:1 compression
    private const val COMPRESSION_THRESHOLD = 0.7f   // Umbral para comprimir
    private const val ATTACK_TIME = 0.005f           // 5 ms
    private const val RELEASE_TIME = 0.05f           // 50 ms
    private const val LOOKAHEAD_SAMPLES = 64

    private val isPcm16 = WeakHashMap<AudioRecord, Boolean>()
    private val fxBySession = ConcurrentHashMap<Int, Pair<AutomaticGainControl?, NoiseSuppressor?>>()

    // Buffers para procesamiento avanzado
    private val compressorStates = ConcurrentHashMap<AudioRecord, CompressorState>()
    private val lookaheadBuffers = ConcurrentHashMap<AudioRecord, FloatArray>()

    @Volatile private var lastActive = false
    @Volatile private var lastReason = "init"
    @Volatile private var lastTs = 0L
    @Volatile private var globalBoostFactor = 4.0f // 4x = 12 dB (default agresivo)

    // ⬇️ Variables correctas para el TTL del “preboost reciente”
    @Volatile private var lastPreboostTs: Long = 0L
    @Volatile private var lastPreboostBoost: Float = 1.0f

    private var totalAudioRecordsHooked = 0
    private var totalPreBoostsApplied = 0

    // Estado del compresor por AudioRecord
    data class CompressorState(
        var envelope: Float = 0f,
        var gain: Float = 1.0f,
        var attackCoeff: Float = 0f,
        var releaseCoeff: Float = 0f,
        var sampleRate: Int = SR_FALLBACK
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        updateGlobalBoostFactor()

        hookAudioRecordCtor()
        hookAudioRecordBuilder(lpparam)
        hookStartRecording()
        hookReadShort()
        hookReadByte()
        hookReadByteBuffer()
        hookRelease()
        hookSystemAudioServices(lpparam)

        if (lpparam.packageName.contains("whatsapp")) {
            Logx.d("Hooks instalados en ${lpparam.packageName} - Boost: ${globalBoostFactor}x")
        }
    }

    private fun updateGlobalBoostFactor() {
        try {
            Prefs.reloadIfStale(100)
            globalBoostFactor = if (Prefs.moduleEnabled && Prefs.enablePreboost) {
                // Permitir hasta 4x (12 dB)
                min(maxOf(Prefs.boostFactor, 1.0f), 4.0f)
            } else {
                1.0f
            }
            Logx.d("Boost factor actualizado: ${globalBoostFactor}x")
        } catch (t: Throwable) {
            globalBoostFactor = 4.0f
            Logx.w("Error leyendo prefs, usando boost default: ${globalBoostFactor}x")
        }
    }

    private fun hookSystemAudioServices(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookConstructor(
                MediaRecorder::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        totalAudioRecordsHooked++
                    }
                }
            )
        } catch (_: Throwable) { }
    }

    // --- Estado a la app -----------------------------------------------------

    private fun emitStatus(active: Boolean, reason: String, ar: AudioRecord?, sid: Int) {
        lastActive = active
        lastReason = reason
        lastTs = System.currentTimeMillis()

        try {
            AppCtx.get()?.sendBroadcast(
                Intent("com.d4vram.whatsmicfix.WFM_STATUS")
                    .setPackage(APP_PKG)
                    .putExtra("active", active)
                    .putExtra("reason", reason)
                    .putExtra("sid", sid)
                    .putExtra("sr", try { ar?.sampleRate ?: -1 } catch (_: Throwable) { -1 })
                    .putExtra("enc", try { ar?.audioFormat ?: -1 } catch (_: Throwable) { -1 })
                    .putExtra("ts", lastTs)
                    .putExtra("boostFactor", globalBoostFactor)
                    // TTL del último preboost (clave + valor correctos)
                    .putExtra(
                        "recentPreboostAgeMs",
                        if (lastPreboostTs == 0L) Long.MAX_VALUE
                        else System.currentTimeMillis() - lastPreboostTs
                    )
                    .putExtra("recentPreboostBoost", lastPreboostBoost)
            )
        } catch (_: Throwable) { }
    }

    fun respondPing() {
        try {
            updateGlobalBoostFactor()
            AppCtx.get()?.sendBroadcast(
                Intent("com.d4vram.whatsmicfix.WFM_STATUS")
                    .setPackage(APP_PKG)
                    .putExtra("active", lastActive)
                    .putExtra("reason", lastReason)
                    .putExtra("ts", lastTs)
                    .putExtra("boostFactor", globalBoostFactor)
                    .putExtra("totalHooked", totalAudioRecordsHooked)
                    .putExtra("totalBoosts", totalPreBoostsApplied)
                    // mismas claves que en emitStatus
                    .putExtra(
                        "recentPreboostAgeMs",
                        if (lastPreboostTs == 0L) Long.MAX_VALUE
                        else System.currentTimeMillis() - lastPreboostTs
                    )
                    .putExtra("recentPreboostBoost", lastPreboostBoost)
            )
        } catch (_: Throwable) { }
    }

    // --- Hooks ---------------------------------------------------------------

    private fun hookAudioRecordCtor() {
        XposedHelpers.findAndHookConstructor(
            AudioRecord::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        Prefs.reloadIfStale(500)
                        if (!Prefs.moduleEnabled) return

                        val origSr  = p.args[1] as Int
                        val origCh  = p.args[2] as Int
                        val origFmt = p.args[3] as Int

                        val validSr = if (origSr in 8000..192000) origSr else SR_FALLBACK
                        val validCh = when (origCh) {
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO -> origCh
                            else -> AudioFormat.CHANNEL_IN_MONO
                        }
                        val validFmt = when (origFmt) {
                            AudioFormat.ENCODING_PCM_16BIT,
                            AudioFormat.ENCODING_PCM_8BIT,
                            AudioFormat.ENCODING_PCM_FLOAT -> origFmt
                            else -> AudioFormat.ENCODING_PCM_16BIT
                        }

                        if (Prefs.forceSourceMic) p.args[0] = MediaRecorder.AudioSource.MIC

                        if (!Prefs.respectAppFormat) {
                            val sr = pickSampleRate(validSr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                            p.args[1] = sr
                            p.args[2] = AudioFormat.CHANNEL_IN_MONO
                            p.args[3] = AudioFormat.ENCODING_PCM_16BIT
                        } else {
                            val minBuf = AudioRecord.getMinBufferSize(validSr, validCh, validFmt)
                            if (minBuf <= 0 || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                                p.args[1] = SR_FALLBACK
                                p.args[2] = AudioFormat.CHANNEL_IN_MONO
                                p.args[3] = AudioFormat.ENCODING_PCM_16BIT
                            } else {
                                p.args[1] = validSr
                                p.args[2] = validCh
                                p.args[3] = validFmt
                            }
                        }

                        Logx.d("AudioRecord ctor: sr=${p.args[1]}, ch=${p.args[2]}, fmt=${p.args[3]}")
                    } catch (t: Throwable) {
                        Logx.e("Error en hook constructor AudioRecord", t)
                        p.args[1] = SR_FALLBACK
                        p.args[2] = AudioFormat.CHANNEL_IN_MONO
                        p.args[3] = AudioFormat.ENCODING_PCM_16BIT
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val ar = p.thisObject as AudioRecord
                    val enc = try { ar.audioFormat } catch (_: Throwable) { AudioFormat.ENCODING_INVALID }
                    isPcm16[ar] = (enc == AudioFormat.ENCODING_PCM_16BIT)

                    val sr = try { ar.sampleRate } catch (_: Throwable) { SR_FALLBACK }
                    initCompressorForRecord(ar, sr)

                    if (Build.VERSION.SDK_INT >= 28 && Prefs.forceSourceMic) {
                        try {
                            AppCtx.get()?.let { ctx ->
                                val am = ctx.getSystemService(AudioManager::class.java)
                                val dev = am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                                if (dev != null) ar.setPreferredDevice(dev)
                            }
                        } catch (_: Throwable) { }
                    }
                }
            }
        )
    }

    private fun initCompressorForRecord(ar: AudioRecord, sampleRate: Int) {
        val attackSamples = (ATTACK_TIME * sampleRate).toInt()
        val releaseSamples = (RELEASE_TIME * sampleRate).toInt()

        compressorStates[ar] = CompressorState(
            envelope = 0f,
            gain = 1.0f,
            // conversiones simples para mantener tipos Float
            attackCoeff = 1f - kotlin.math.exp((-1.0 / attackSamples)).toFloat(),
            releaseCoeff = 1f - kotlin.math.exp((-1.0 / releaseSamples)).toFloat(),
            sampleRate = sampleRate
        )

        lookaheadBuffers[ar] = FloatArray(LOOKAHEAD_SAMPLES)
    }

    private fun pickSampleRate(requested: Int, channels: Int, format: Int): Int {
        val rates = intArrayOf(requested, SR_HIGH, SR_FALLBACK, 32000, 22050, 16000, 11025, 8000)
        for (rate in rates) {
            if (rate > 0) {
                val bufSize = AudioRecord.getMinBufferSize(rate, channels, format)
                if (bufSize > 0 && bufSize != AudioRecord.ERROR_BAD_VALUE) return rate
            }
        }
        return SR_FALLBACK
    }

    private fun hookAudioRecordBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderCls = XposedHelpers.findClass("android.media.AudioRecord\$Builder", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(builderCls, "setAudioSource",
                Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        Prefs.reloadIfStale(500)
                        if (Prefs.forceSourceMic) p.args[0] = MediaRecorder.AudioSource.MIC
                    }
                })

            XposedHelpers.findAndHookMethod(builderCls, "setAudioFormat",
                AudioFormat::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        Prefs.reloadIfStale(500)
                        val inFmt = p.args[0] as AudioFormat? ?: return

                        if (!Prefs.respectAppFormat) {
                            val sr = pickSampleRate(inFmt.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                            p.args[0] = AudioFormat.Builder()
                                .setSampleRate(sr)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        } else {
                            val srOk = inFmt.sampleRate > 0 &&
                                    AudioRecord.getMinBufferSize(inFmt.sampleRate, inFmt.channelMask, inFmt.encoding) > 0
                            val sr = if (srOk) inFmt.sampleRate else SR_FALLBACK
                            val enc = if (inFmt.encoding == AudioFormat.ENCODING_INVALID)
                                AudioFormat.ENCODING_PCM_16BIT else inFmt.encoding
                            if (sr != inFmt.sampleRate || enc != inFmt.encoding) {
                                p.args[0] = AudioFormat.Builder()
                                    .setSampleRate(sr)
                                    .setChannelMask(inFmt.channelMask)
                                    .setEncoding(enc)
                                    .build()
                            }
                        }
                    }
                })

            XposedHelpers.findAndHookMethod(builderCls, "build", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val ar = p.result as? AudioRecord ?: return
                    val enc = try { ar.audioFormat } catch (_: Throwable) { AudioFormat.ENCODING_INVALID }
                    isPcm16[ar] = (enc == AudioFormat.ENCODING_PCM_16BIT)

                    val sr = try { ar.sampleRate } catch (_: Throwable) { SR_FALLBACK }
                    initCompressorForRecord(ar, sr)

                    if (Build.VERSION.SDK_INT >= 28 && Prefs.forceSourceMic) {
                        try {
                            AppCtx.get()?.let { ctx ->
                                val am = ctx.getSystemService(AudioManager::class.java)
                                val dev = am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                                if (dev != null) ar.setPreferredDevice(dev)
                            }
                        } catch (_: Throwable) { }
                    }
                }
            })
        } catch (t: Throwable) {
            Logx.e("No se pudo hookear AudioRecord.Builder", t)
        }
    }

    private fun hookStartRecording() {
        XposedHelpers.findAndHookMethod(AudioRecord::class.java, "startRecording", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                Prefs.reloadIfStale(500)
                if (!Prefs.moduleEnabled) return

                val ar = p.thisObject as AudioRecord
                val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
                if (sid <= 0) return

                if (!fxBySession.containsKey(sid)) {
                    val agc = if (Prefs.enableAgc && AutomaticGainControl.isAvailable()) {
                        try { AutomaticGainControl.create(sid)?.apply { enabled = true } } catch (_: Throwable) { null }
                    } else null

                    val ns = if (Prefs.enableNs && NoiseSuppressor.isAvailable()) {
                        try { NoiseSuppressor.create(sid)?.apply { enabled = true } } catch (_: Throwable) { null }
                    } else null

                    fxBySession[sid] = Pair(agc, ns)
                }

                emitStatus(Prefs.enablePreboost && (isPcm16[ar] == true), "start", ar, sid)
            }
        })
    }

    private fun hookRelease() {
        XposedHelpers.findAndHookMethod(AudioRecord::class.java, "release", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val ar = p.thisObject as AudioRecord
                val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
                if (sid > 0) {
                    fxBySession.remove(sid)?.let { (agc, ns) ->
                        try { agc?.release() } catch (_: Throwable) { }
                        try { ns?.release() } catch (_: Throwable) { }
                        Logx.d("FX liberados sid=$sid")
                    }
                }
                isPcm16.remove(ar)
                compressorStates.remove(ar)
                lookaheadBuffers.remove(ar)
                emitStatus(false, "release", ar, sid)
            }
        })
    }

    private fun hookReadShort() {
        XposedHelpers.findAndHookMethod(
            AudioRecord::class.java, "read",
            ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val count = (p.result as? Int) ?: return
                    if (count <= 0) return

                    val ar = p.thisObject as AudioRecord
                    if (!Prefs.moduleEnabled || !Prefs.enablePreboost || isPcm16[ar] != true) return

                    val buf = p.args[0] as ShortArray
                    val off = p.args[1] as Int
                    val len = min(count, buf.size - off)
                    if (len <= 0) return

                    processWithCompressor(buf, off, len, globalBoostFactor, ar)
                    totalPreBoostsApplied++

                    val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }

                    // marca el preboost reciente
                    lastPreboostTs = System.currentTimeMillis()
                    lastPreboostBoost = globalBoostFactor

                    emitStatus(true, "preboost", ar, sid)
                }
            }
        )
    }

    private fun hookReadByte() {
        XposedHelpers.findAndHookMethod(
            AudioRecord::class.java, "read",
            ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val count = (p.result as? Int) ?: return
                    if (count <= 0) return

                    val ar = p.thisObject as AudioRecord
                    if (!Prefs.moduleEnabled || !Prefs.enablePreboost || isPcm16[ar] != true) return

                    val buf = p.args[0] as ByteArray
                    val off = p.args[1] as Int
                    val len = min(count, buf.size - off)
                    if (len <= 1) return

                    processBytesWithCompressor(buf, off, len, globalBoostFactor, ar)
                    totalPreBoostsApplied++

                    val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }

                    lastPreboostTs = System.currentTimeMillis()
                    lastPreboostBoost = globalBoostFactor

                    emitStatus(true, "preboost", ar, sid)
                }
            }
        )
    }

    private fun hookReadByteBuffer() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                ByteBuffer::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val count = (p.result as? Int) ?: return
                        if (count <= 0) return

                        val ar = p.thisObject as AudioRecord
                        if (!Prefs.moduleEnabled || !Prefs.enablePreboost || isPcm16[ar] != true) return

                        val bb = p.args[0] as ByteBuffer
                        val valid = min(count, bb.remaining())
                        if (valid <= 1) return

                        processByteBufferWithCompressor(bb, valid, globalBoostFactor, ar)
                        totalPreBoostsApplied++

                        val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }

                        lastPreboostTs = System.currentTimeMillis()
                        lastPreboostBoost = globalBoostFactor

                        emitStatus(true, "preboost", ar, sid)
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("No se pudo hookear read(ByteBuffer,...)", t)
        }
    }

    // --- Procesamiento -------------------------------------------------------

    private fun processWithCompressor(buf: ShortArray, off: Int, len: Int, boost: Float, ar: AudioRecord) {
        val state = compressorStates[ar] ?: return
        val end = off + len
        var i = off

        while (i < end) {
            val input = buf[i].toFloat() / Short.MAX_VALUE
            val boosted = input * boost
            val level = abs(boosted)

            if (level > state.envelope) {
                state.envelope += (level - state.envelope) * state.attackCoeff
            } else {
                state.envelope += (level - state.envelope) * state.releaseCoeff
            }

            val targetGain = if (state.envelope > COMPRESSION_THRESHOLD) {
                val excess = state.envelope - COMPRESSION_THRESHOLD
                val compressedExcess = excess / COMPRESSION_RATIO
                (COMPRESSION_THRESHOLD + compressedExcess) / state.envelope
            } else 1.0f

            state.gain += (targetGain - state.gain) * 0.1f

            val compressed = boosted * state.gain
            val output = if (abs(compressed) > 0.95f) 0.95f * tanh(compressed / 0.95f) else compressed

            buf[i] = (output * Short.MAX_VALUE).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort()

            i++
        }
    }

    private fun processBytesWithCompressor(buf: ByteArray, off: Int, len: Int, boost: Float, ar: AudioRecord) {
        val state = compressorStates[ar] ?: return
        val end = off + len - 1
        var i = off

        while (i < end) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()

            val input = sample.toFloat() / Short.MAX_VALUE
            val boosted = input * boost
            val level = abs(boosted)

            if (level > state.envelope) {
                state.envelope += (level - state.envelope) * state.attackCoeff
            } else {
                state.envelope += (level - state.envelope) * state.releaseCoeff
            }

            val targetGain = if (state.envelope > COMPRESSION_THRESHOLD) {
                val excess = state.envelope - COMPRESSION_THRESHOLD
                val compressedExcess = excess / COMPRESSION_RATIO
                (COMPRESSION_THRESHOLD + compressedExcess) / state.envelope
            } else 1.0f

            state.gain += (targetGain - state.gain) * 0.1f

            val compressed = boosted * state.gain
            val output = if (abs(compressed) > 0.95f) 0.95f * tanh(compressed / 0.95f) else compressed

            val out = (output * Short.MAX_VALUE).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort()

            buf[i] = (out.toInt() and 0xFF).toByte()
            buf[i + 1] = ((out.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun processByteBufferWithCompressor(bb: ByteBuffer, validBytes: Int, boost: Float, ar: AudioRecord) {
        val state = compressorStates[ar] ?: return

        val savedOrder = bb.order()
        bb.order(ByteOrder.LITTLE_ENDIAN)

        val pos = bb.position()
        val lim = pos + validBytes
        var i = pos

        while (i + 1 < lim) {
            val sample = bb.getShort(i)
            val input = sample.toFloat() / Short.MAX_VALUE
            val boosted = input * boost
            val level = abs(boosted)

            if (level > state.envelope) {
                state.envelope += (level - state.envelope) * state.attackCoeff
            } else {
                state.envelope += (level - state.envelope) * state.releaseCoeff
            }

            val targetGain = if (state.envelope > COMPRESSION_THRESHOLD) {
                val excess = state.envelope - COMPRESSION_THRESHOLD
                val compressedExcess = excess / COMPRESSION_RATIO
                (COMPRESSION_THRESHOLD + compressedExcess) / state.envelope
            } else 1.0f

            state.gain += (targetGain - state.gain) * 0.1f

            val compressed = boosted * state.gain
            val output = if (abs(compressed) > 0.95f) 0.95f * tanh(compressed / 0.95f) else compressed

            bb.putShort(i, (output * Short.MAX_VALUE).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort())

            i += 2
        }

        bb.order(savedOrder)
    }
}
