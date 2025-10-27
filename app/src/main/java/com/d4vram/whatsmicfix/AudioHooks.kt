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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.tanh

private const val APP_PKG = "com.d4vram.whatsmicfix"

object AudioHooks {

    private const val SR_FALLBACK = 44100
    private const val SR_HIGH = 48000

    private const val COMPRESSION_RATIO = 4.0f
    private const val COMPRESSION_THRESHOLD = 0.7f
    private const val ATTACK_TIME = 0.005f
    private const val RELEASE_TIME = 0.05f
    private const val LOOKAHEAD_SAMPLES = 64

    private val isPcm16 = ConcurrentHashMap<AudioRecord, Boolean>()
    private val fxBySession = ConcurrentHashMap<Int, Pair<AutomaticGainControl?, NoiseSuppressor?>>()
    private val compressorStates = ConcurrentHashMap<AudioRecord, CompressorState>()
    private val lookaheadBuffers = ConcurrentHashMap<AudioRecord, FloatArray>()

    @Volatile private var lastActive = false
    @Volatile private var lastReason = "init"
    @Volatile private var lastTs = 0L
    @Volatile private var globalBoostFactor = 4.0f
    @Volatile private var lastBoostUpdateTime = 0L  // Para optimizar reloads

    private var totalAudioRecordsHooked = 0
    private var totalPreBoostsApplied = 0

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
        hookMediaRecorderMethods()
        hookAlternativeAudioMethods()
        hookUniversalAudioRecordRead()
        hookRelease()
        hookReadFloat()
        hookSystemAudioServices(lpparam)
        if (lpparam.packageName.contains("whatsapp")) {
            Logx.d("Hooks instalados en ${lpparam.packageName} - Boost: ${globalBoostFactor}x")
        }
    }

    private fun updateGlobalBoostFactor() {
        try {
            Prefs.reloadIfStale(100)
            globalBoostFactor = if (Prefs.moduleEnabled && Prefs.enablePreboost)
                min(maxOf(Prefs.boostFactor, 1.0f), 4.0f)
            else 1.0f
            lastBoostUpdateTime = System.currentTimeMillis()
            Logx.d("Boost factor actualizado: ${globalBoostFactor}x")
        } catch (t: Throwable) {
            globalBoostFactor = 4.0f
            Logx.w("Error leyendo prefs, usando boost default: ${globalBoostFactor}x")
        }
    }

    private fun updateGlobalBoostFactorIfNeeded() {
        // Optimización: Solo recargar si han pasado >3 segundos desde la última actualización
        // Esto evita lecturas de disco frecuentes que causan lag en el UI
        val now = System.currentTimeMillis()
        if (now - lastBoostUpdateTime > 3000) {
            updateGlobalBoostFactor()
        }
        // Si no han pasado 3 segundos, usar el valor cacheado (ya está en globalBoostFactor)
    }

    private fun hookSystemAudioServices(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookConstructor(MediaRecorder::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) { totalAudioRecordsHooked++ }
            })
        } catch (_: Throwable) {}
    }

    private fun emitStatus(active: Boolean, reason: String, ar: AudioRecord?, sid: Int) {
        lastActive = active; lastReason = reason; lastTs = System.currentTimeMillis()
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
                    .putExtra("totalBoosts", totalPreBoostsApplied) // <-- añadido
            )
        } catch (t: Throwable) {
            Logx.w("Error en emitStatus: ${t.message}")
        }
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
            )
        } catch (_: Throwable) {}
    }

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
                            AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_FLOAT -> origFmt
                            else -> AudioFormat.ENCODING_PCM_16BIT
                        }

                        if (Prefs.forceSourceMic) p.args[0] = MediaRecorder.AudioSource.MIC

                        if (!Prefs.respectAppFormat) {
                            val sr = pickSampleRate(validSr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                            p.args[1] = sr
                            p.args[2] = AudioFormat.CHANNEL_IN_MONO
                            p.args[3] = AudioFormat.ENCODING_PCM_16BIT
                        } else {
                            val minBufSize = AudioRecord.getMinBufferSize(validSr, validCh, validFmt)
                            if (minBufSize <= 0 || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
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
                        } catch (_: Throwable) {}
                    }
                }
            }
        )
    }

    private fun initCompressorForRecord(ar: AudioRecord, sampleRate: Int) {
        val attackSamples = (ATTACK_TIME * sampleRate).toInt().coerceAtLeast(1)
        val releaseSamples = (RELEASE_TIME * sampleRate).toInt().coerceAtLeast(1)

        compressorStates[ar] = CompressorState(
            envelope = 0f,
            gain = 1.0f,
            attackCoeff = 1f - kotlin.math.exp(-1f / attackSamples),
            releaseCoeff = 1f - kotlin.math.exp(-1f / releaseSamples),
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
                        } catch (_: Throwable) {}
                    }
                }
            })
        } catch (t: Throwable) {
            Logx.e("No se pudo hookear AudioRecord.Builder", t)
        }
    }

    private fun hookStartRecording() {
        XposedHelpers.findAndHookMethod(AudioRecord::class.java, "startRecording", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                // OPTIMIZACIÓN: Operaciones mínimas para evitar lag en UI de WhatsApp

                // CRÍTICO 1: Actualizar boost solo si es necesario (cada 3+ segundos)
                updateGlobalBoostFactorIfNeeded()

                // CRÍTICO 2: Resetear estado del compresor (operación rápida)
                val ar = p.thisObject as AudioRecord
                val sr = try { ar.sampleRate } catch (_: Throwable) { SR_FALLBACK }

                compressorStates[ar]?.let { state ->
                    // Compresor ya existe: solo resetear estado (muy rápido)
                    state.envelope = 0f
                    state.gain = 1.0f
                    state.sampleRate = sr
                } ?: run {
                    // No existe: inicializar (solo primera vez por AudioRecord)
                    initCompressorForRecord(ar, sr)
                }

                // CRÍTICO 3: Detección de formato SOLO si no está en caché
                if (!isPcm16.containsKey(ar)) {
                    // Primera vez para este AudioRecord: detectar formato
                    val detectedFormat = try {
                        ar.audioFormat
                    } catch (_: Throwable) {
                        AudioFormat.ENCODING_INVALID
                    }

                    isPcm16[ar] = when (detectedFormat) {
                        AudioFormat.ENCODING_PCM_16BIT -> true
                        AudioFormat.ENCODING_INVALID, AudioFormat.ENCODING_DEFAULT -> true  // Modo permisivo
                        AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_FLOAT -> false
                        else -> true  // Intentar con formatos desconocidos
                    }
                }

                // Nota: Logs movidos a afterHookedMethod para no bloquear
            }

            override fun afterHookedMethod(p: MethodHookParam) {
                val timestamp = System.currentTimeMillis()
                val ar = p.thisObject as AudioRecord

                // Log informativo (no bloquea el inicio)
                Logx.d("[$timestamp] startRecording() COMPLETO - boost=${globalBoostFactor}x, PCM16=${isPcm16[ar]}")

                if (!Prefs.moduleEnabled) {
                    return
                }

                val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
                if (sid <= 0) return

                // Crear efectos de audio (AGC, NoiseSuppressor) si no existen
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
                        try { agc?.release() } catch (_: Throwable) {}
                        try { ns?.release() } catch (_: Throwable) {}
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
                    val timestamp = System.currentTimeMillis()
                    val count = (p.result as? Int) ?: return
                    Logx.d("[$timestamp] read(ShortArray) called, returned $count samples")
                    if (count <= 0) return

                    val ar = p.thisObject as AudioRecord
                    ensureCompressorInitialized(ar)

                    if (!Prefs.moduleEnabled || !Prefs.enablePreboost) {
                        Logx.d("[$timestamp] read(ShortArray): SKIPPED - enabled=${Prefs.moduleEnabled}, preboost=${Prefs.enablePreboost}")
                        return
                    }

                    if (!ensurePcm16(ar, observedSamples = true)) {
                        // ALERTA: Esto NO debería ocurrir con el nuevo modo permisivo
                        Logx.w("[$timestamp] ⚠️ ALERTA: read(ShortArray) RECHAZADO por formato no-PCM16 - cachedValue=${isPcm16[ar]}, actualFormat=${try { ar.audioFormat } catch (_: Throwable) { "ERROR" }}")
                        return
                    }

                    val buf = p.args[0] as ShortArray
                    val off = p.args[1] as Int
                    val len = min(count, buf.size - off)
                    if (len <= 0) return

                    Logx.d("[$timestamp] read(ShortArray): APLICANDO BOOST ${globalBoostFactor}x a $len samples (boost #${totalPreBoostsApplied + 1})")

                    processWithCompressor(buf, off, len, globalBoostFactor, ar)
                    totalPreBoostsApplied++
                    val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
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
                    val timestamp = System.currentTimeMillis()
                    val count = (p.result as? Int) ?: return
                    if (count <= 0) return

                    val ar = p.thisObject as AudioRecord
                    ensureCompressorInitialized(ar)
                    if (!Prefs.moduleEnabled || !Prefs.enablePreboost) {
                        Logx.d("[$timestamp] read(ByteArray): SKIPPED - enabled=${Prefs.moduleEnabled}, preboost=${Prefs.enablePreboost}")
                        return
                    }
                    if (!ensurePcm16(ar, observedSamples = (count >= 2))) {
                        // ALERTA: Esto NO debería ocurrir con el nuevo modo permisivo
                        Logx.w("[$timestamp] ⚠️ ALERTA: read(ByteArray) RECHAZADO por formato no-PCM16 - cachedValue=${isPcm16[ar]}, actualFormat=${try { ar.audioFormat } catch (_: Throwable) { "ERROR" }}")
                        return
                    }

                    val buf = p.args[0] as ByteArray
                    val off = (p.args[1] as Int).coerceAtLeast(0)
                    val req = (p.args[2] as Int).coerceAtLeast(0)
                    val len = min(min(count, req), buf.size - off).let {
                        if (it % 2 != 0) it - 1 else it
                    }
                    if (len <= 1) return

                    Logx.d("[$timestamp] read(ByteArray): APLICANDO BOOST ${globalBoostFactor}x a $len bytes (boost #${totalPreBoostsApplied + 1})")

                    processBytesWithCompressor(buf, off, len, globalBoostFactor, ar)
                    totalPreBoostsApplied++
                    val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
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
                        ensureCompressorInitialized(ar)
                        if (!Prefs.moduleEnabled || !Prefs.enablePreboost) return

                        val bb = p.args[0] as ByteBuffer
                        val endPos = bb.position().coerceAtLeast(0)
                        val startPos = (endPos - count).coerceAtLeast(0)
                        val span = min(count, bb.limit() - startPos)
                        val evenSpan = if (span % 2 != 0) span - 1 else span
                        if (evenSpan <= 1) return
                        if (!ensurePcm16(ar, observedSamples = (evenSpan >= 2))) return

                        processByteBufferWithCompressor(bb, startPos, evenSpan, globalBoostFactor, ar)
                        totalPreBoostsApplied++
                        val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
                        emitStatus(true, "preboost", ar, sid)
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("No se pudo hookear read(ByteBuffer,...)", t)
        }
    }

    private fun hookReadFloat() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java, "read",
                FloatArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val count = (p.result as? Int) ?: return
                        if (count <= 0) return

                        val ar = p.thisObject as AudioRecord
                        Prefs.reloadIfStale(500)
                        if (!Prefs.moduleEnabled || !Prefs.enablePreboost) return

                        val buf = p.args[0] as FloatArray
                        val off = p.args[1] as Int
                        val len = kotlin.math.min(count, buf.size - off)
                        if (len <= 0) return

                        processFloatArray(buf, off, len, globalBoostFactor)

                        totalPreBoostsApplied++
                        val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
                        emitStatus(true, "preboost", ar, sid)
                    }
                }
            )
        } catch (_: Throwable) { /* en algunos OEM no existe la sobrecarga float */ }
    }

    // --- Procesamiento (boost + compresor + soft clip) ---

    private fun processWithCompressor(buf: ShortArray, off: Int, len: Int, boost: Float, ar: AudioRecord) {
        val state = compressorStates[ar] ?: return
        val end = off + len
        var i = off
        while (i < end) {
            val input = buf[i].toFloat() / Short.MAX_VALUE
            val boosted = input * boost
            val level = abs(boosted)

            // envelope
            state.envelope += ((if (level > state.envelope) ATTACK_TIME else RELEASE_TIME) * (level - state.envelope))

            // ganancia target
            val targetGain = if (state.envelope > COMPRESSION_THRESHOLD) {
                val excess = state.envelope - COMPRESSION_THRESHOLD
                val compressedExcess = excess / COMPRESSION_RATIO
                (COMPRESSION_THRESHOLD + compressedExcess) / state.envelope
            } else 1.0f

            state.gain += (targetGain - state.gain) * 0.1f

            val compressed = boosted * state.gain
            val out = if (abs(compressed) > 0.95f) 0.95f * tanh(compressed / 0.95f) else compressed

            buf[i] = (out * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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

            state.envelope += ((if (level > state.envelope) ATTACK_TIME else RELEASE_TIME) * (level - state.envelope))

            val targetGain = if (state.envelope > COMPRESSION_THRESHOLD) {
                val excess = state.envelope - COMPRESSION_THRESHOLD
                val compressedExcess = excess / COMPRESSION_RATIO
                (COMPRESSION_THRESHOLD + compressedExcess) / state.envelope
            } else 1.0f

            state.gain += (targetGain - state.gain) * 0.1f

            val compressed = boosted * state.gain
            val out = if (abs(compressed) > 0.95f) 0.95f * tanh(compressed / 0.95f) else compressed

            val outShort = (out * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buf[i]     = (outShort.toInt() and 0xFF).toByte()
            buf[i + 1] = ((outShort.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun processByteBufferWithCompressor(bb: ByteBuffer, start: Int, bytes: Int, boost: Float, ar: AudioRecord) {
        val state = compressorStates[ar] ?: return
        val savedOrder = bb.order()
        val savedPos = bb.position()
        bb.order(ByteOrder.LITTLE_ENDIAN)
        val startIdx = start.coerceAtLeast(0)
        val limit = min(bb.limit(), startIdx + bytes)
        var i = startIdx
        while (i + 1 < limit) {
            val sample = bb.getShort(i)
            val input = sample.toFloat() / Short.MAX_VALUE
            val boosted = input * boost
            val level = abs(boosted)

            state.envelope += ((if (level > state.envelope) ATTACK_TIME else RELEASE_TIME) * (level - state.envelope))

            val targetGain = if (state.envelope > COMPRESSION_THRESHOLD) {
                val excess = state.envelope - COMPRESSION_THRESHOLD
                val compressedExcess = excess / COMPRESSION_RATIO
                (COMPRESSION_THRESHOLD + compressedExcess) / state.envelope
            } else 1.0f

            state.gain += (targetGain - state.gain) * 0.1f

            val compressed = boosted * state.gain
            val out = if (abs(compressed) > 0.95f) 0.95f * tanh(compressed / 0.95f) else compressed

            bb.putShort(i, (out * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            i += 2
        }
        bb.position(savedPos)
        bb.order(savedOrder)
    }

    private fun processFloatArray(buf: FloatArray, off: Int, len: Int, boost: Float) {
        val end = off + len
        var i = off
        while (i < end) {
            var sample = buf[i] * boost
            if (abs(sample) > 0.95f) {
                sample = 0.95f * tanh(sample / 0.95f)
            }
            buf[i] = sample
            i++
        }
    }

    private fun hookMediaRecorderMethods() {
        try {
            // Hook MediaRecorder.start() - Muchas apps usan esto en lugar de AudioRecord
            XposedHelpers.findAndHookMethod(
                "android.media.MediaRecorder",
                null,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        Logx.d("MediaRecorder.start() CALLED - starting recording")
                        markAudioActive("MediaRecorder.start()")
                    }
                }
            )

            // Hook MediaRecorder.prepare()
            XposedHelpers.findAndHookMethod(
                "android.media.MediaRecorder",
                null,
                "prepare",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        Logx.d("MediaRecorder.prepare() CALLED")
                    }
                }
            )

            // Hook MediaRecorder.setAudioSource()
            XposedHelpers.findAndHookMethod(
                "android.media.MediaRecorder",
                null,
                "setAudioSource",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val source = p.args[0] as Int
                        Logx.d("MediaRecorder.setAudioSource($source) CALLED")
                        if (source == 1) { // MediaRecorder.AudioSource.MIC
                            Logx.d("MediaRecorder using MICROPHONE source - this is what we want to intercept!")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("Error hooking MediaRecorder methods", t)
        }
    }

    private fun hookAlternativeAudioMethods() {
        try {
            // Hook AudioRecord métodos alternativos que WhatsApp podría usar
            
            // AudioRecord.read con diferentes parámetros
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java,
                "read",
                FloatArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val count = (p.result as? Int) ?: return
                        if (count <= 0) return

                        val ar = p.thisObject as AudioRecord
                        Prefs.reloadIfStale(500)
                        if (!Prefs.moduleEnabled || !Prefs.enablePreboost) return

                        val buf = p.args[0] as FloatArray
                        val off = (p.args[1] as Int).coerceAtLeast(0)
                        val req = (p.args[2] as Int).coerceAtLeast(0)
                        val len = min(min(count, req), buf.size - off).coerceAtLeast(0)
                        if (len <= 0) return

                        processFloatArray(buf, off, len, globalBoostFactor)
                        totalPreBoostsApplied++
                        val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
                        emitStatus(true, "preboost", ar, sid)
                    }
                }
            )

            // Hook AudioRecord.getMinBufferSize - se llama antes de grabar
            XposedHelpers.findAndHookMethod(
                AudioRecord::class.java,
                "getMinBufferSize",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val sr = p.args[0] as Int
                        val ch = p.args[1] as Int
                        val fmt = p.args[2] as Int
                        Logx.d("AudioRecord.getMinBufferSize(sr=$sr, ch=$ch, fmt=$fmt) - preparing for recording")
                    }
                }
            )

            // Hook AudioTrack methods que pueden estar involucrados
            XposedHelpers.findAndHookMethod(
                "android.media.AudioTrack",
                null,
                "write",
                ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val count = p.args[2] as Int
                        if (count > 0) {
                            Logx.d("AudioTrack.write(ShortArray, $count) - audio playback detected")
                            markAudioActive("AudioTrack.write()")
                        }
                    }
                }
            )

        } catch (t: Throwable) {
            Logx.e("Error hooking alternative audio methods", t)
        }
    }

    private fun markAudioActive(source: String) {
        try {
            lastActive = true
            lastReason = source
            lastTs = System.currentTimeMillis()
            Logx.d("AUDIO ACTIVITY DETECTED from: $source")
            
            // Enviar broadcast inmediato para notificar detección
            AppCtx.get()?.sendBroadcast(
                Intent("com.d4vram.whatsmicfix.WFM_STATUS")
                    .setPackage(APP_PKG)
                    .putExtra("active", true)
                    .putExtra("reason", source)
                    .putExtra("ts", lastTs)
                    .putExtra("boostFactor", globalBoostFactor)
            )
        } catch (t: Throwable) {
            Logx.e("Error marking audio active", t)
        }
    }

    private fun hookUniversalAudioRecordRead() {
        try {
            // Hook usando reflexión para interceptar TODOS los métodos read de AudioRecord
            val audioRecordClass = AudioRecord::class.java
            val readMethods = audioRecordClass.declaredMethods.filter { it.name == "read" }
            
            Logx.d("Found ${readMethods.size} read methods in AudioRecord class")
            
            for (method in readMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        audioRecordClass,
                        method.name,
                        *method.parameterTypes,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(p: MethodHookParam) {
                                val result = p.result
                                val paramTypes = method.parameterTypes.map { it.simpleName }.joinToString(", ")
                                
                                Logx.d("UNIVERSAL: AudioRecord.read($paramTypes) called -> result=$result")
                                
                                if (result is Int && result > 0) {
                                    markAudioActive("AudioRecord.read($paramTypes)")
                                    Logx.d("UNIVERSAL READ PROCESSING: $result samples with boost=${globalBoostFactor}x")
                                    
                                    val ar = p.thisObject as AudioRecord
                                    if (!Prefs.moduleEnabled || !Prefs.enablePreboost) return

                                    when {
                                        method.parameterTypes.any { it == ShortArray::class.java } -> {
                                            val buf = p.args.find { it is ShortArray } as? ShortArray
                                            if (buf != null) {
                                                ensureCompressorInitialized(ar)
                                                if (ensurePcm16(ar, observedSamples = true)) {
                                                    val (off, len) = resolveArrayBounds(method.parameterTypes, p.args, buf.size, result, enforceEven = false)
                                                    applyPreBoostShortArray(ar, buf, off, len)
                                                    Logx.d("UNIVERSAL: Applied pre-boost to $len short samples!")
                                                    totalPreBoostsApplied++
                                                }
                                            }
                                        }
                                        method.parameterTypes.any { it == ByteArray::class.java } -> {
                                            val buf = p.args.find { it is ByteArray } as? ByteArray
                                            if (buf != null) {
                                                ensureCompressorInitialized(ar)
                                                if (ensurePcm16(ar, observedSamples = (result >= 2))) {
                                                    val (off, len) = resolveArrayBounds(method.parameterTypes, p.args, buf.size, result, enforceEven = true)
                                                    if (len > 1) {
                                                        processBytesWithCompressor(buf, off, len, globalBoostFactor, ar)
                                                        Logx.d("UNIVERSAL: Applied pre-boost to $len bytes!")
                                                        totalPreBoostsApplied++
                                                    }
                                                }
                                            }
                                        }
                                        method.parameterTypes.any { it == ByteBuffer::class.java } -> {
                                            val bb = p.args.find { it is ByteBuffer } as? ByteBuffer
                                            if (bb != null) {
                                                ensureCompressorInitialized(ar)
                                                val endPos = bb.position().coerceAtLeast(0)
                                                val startPos = (endPos - result).coerceAtLeast(0)
                                                val span = min(result, bb.limit() - startPos)
                                                val evenSpan = if (span % 2 != 0) span - 1 else span
                                                if (evenSpan > 1 && ensurePcm16(ar, observedSamples = (evenSpan >= 2))) {
                                                    processByteBufferWithCompressor(bb, startPos, evenSpan, globalBoostFactor, ar)
                                                    Logx.d("UNIVERSAL: Applied pre-boost to $evenSpan bytes in ByteBuffer!")
                                                    totalPreBoostsApplied++
                                                }
                                            }
                                        }
                                        method.parameterTypes.any { it == FloatArray::class.java } -> {
                                            val buf = p.args.find { it is FloatArray } as? FloatArray
                                            if (buf != null) {
                                                val (off, len) = resolveArrayBounds(method.parameterTypes, p.args, buf.size, result, enforceEven = false)
                                                if (len > 0) {
                                                    processFloatArray(buf, off, len, globalBoostFactor)
                                                    Logx.d("UNIVERSAL: Applied pre-boost to $len floats!")
                                                    totalPreBoostsApplied++
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )
                    Logx.d("Successfully hooked AudioRecord.read(${method.parameterTypes.map { it.simpleName }.joinToString(", ")})")
                } catch (t: Throwable) {
                    Logx.w("Failed to hook read method: ${method.parameterTypes.map { it.simpleName }}")
                }
            }
        } catch (t: Throwable) {
            Logx.e("Error setting up universal AudioRecord read hooks", t)
        }
    }

    private fun applyPreBoostShortArray(ar: AudioRecord, buf: ShortArray, offset: Int, count: Int) {
        val state = compressorStates[ar] ?: return
        val end = kotlin.math.min(offset + count, buf.size)
        for (i in offset until end) {
            val sample = buf[i].toFloat() / Short.MAX_VALUE
            val boosted = sample * globalBoostFactor
            val level = abs(boosted)

            state.envelope += ((if (level > state.envelope) ATTACK_TIME else RELEASE_TIME) * (level - state.envelope))

            val targetGain = if (state.envelope > COMPRESSION_THRESHOLD) {
                val excess = state.envelope - COMPRESSION_THRESHOLD
                val compressedExcess = excess / COMPRESSION_RATIO
                (COMPRESSION_THRESHOLD + compressedExcess) / state.envelope
            } else 1.0f

            state.gain += (targetGain - state.gain) * 0.1f

            val compressed = boosted * state.gain
            val out = if (abs(compressed) > 0.95f) 0.95f * tanh(compressed / 0.95f) else compressed

            buf[i] = (out * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun ensureCompressorInitialized(ar: AudioRecord) {
        if (compressorStates.containsKey(ar)) return
        val sr = try { ar.sampleRate } catch (_: Throwable) { SR_FALLBACK }
        initCompressorForRecord(ar, sr)
    }

    private fun ensurePcm16(ar: AudioRecord, observedSamples: Boolean): Boolean {
        // CAPA 1: Usar caché si existe (ya lo verificamos en startRecording)
        val cached = isPcm16[ar]
        if (cached != null) return cached

        // CAPA 2: Intentar detectar el formato actual
        val encNow = try { ar.audioFormat } catch (_: Throwable) { AudioFormat.ENCODING_INVALID }

        // CAPA 3: MODO PERMISIVO - "inocente hasta que se demuestre lo contrario"
        val is16 = when (encNow) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                Logx.d("ensurePcm16: CONFIRMADO PCM_16BIT")
                true
            }
            AudioFormat.ENCODING_DEFAULT, AudioFormat.ENCODING_INVALID -> {
                // Si no sabemos, ASUMIR que es PCM16 (usado por WhatsApp y 99% de apps)
                Logx.d("ensurePcm16: Formato desconocido/inválido - ASUMIENDO PCM16 (modo permisivo)")
                true  // ← Cambio crítico: true en lugar de observedSamples
            }
            AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_FLOAT -> {
                // Solo si CONFIRMAMOS que NO es PCM16, retornar false
                Logx.d("ensurePcm16: Formato confirmado NO-PCM16: $encNow")
                false
            }
            else -> {
                // Otros formatos exóticos: intentar de todas formas (mejor intentar que fallar)
                Logx.d("ensurePcm16: Formato desconocido $encNow - INTENTANDO procesar como PCM16")
                true
            }
        }

        // Guardar en caché para próximas llamadas
        isPcm16[ar] = is16
        return is16
    }

    private fun resolveArrayBounds(
        paramTypes: Array<Class<*>>,
        args: Array<out Any?>,
        arraySize: Int,
        bytesRead: Int,
        enforceEven: Boolean
    ): Pair<Int, Int> {
        var offset = 0
        var requested = bytesRead
        var intsSeen = 0
        paramTypes.forEachIndexed { index, clazz ->
            if (clazz == Int::class.javaPrimitiveType) {
                val value = (args.getOrNull(index) as? Int) ?: return@forEachIndexed
                when (intsSeen) {
                    0 -> offset = value
                    1 -> requested = value
                }
                intsSeen++
            }
        }
        val maxLen = min(arraySize - offset, requested)
        val len = min(bytesRead, maxLen).coerceAtLeast(0)
        val finalLen = if (enforceEven && (len % 2 != 0)) len - 1 else len
        return offset to finalLen
    }
}
