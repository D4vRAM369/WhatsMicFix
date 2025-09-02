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
import kotlin.math.min

private const val APP_PKG = "com.d4vram.whatsmicfix"

object AudioHooks {

    private const val SR_FALLBACK = 44100
    private const val SR_HIGH = 48000

    private val isPcm16 = WeakHashMap<AudioRecord, Boolean>()
    private val fxBySession = ConcurrentHashMap<Int, Pair<AutomaticGainControl?, NoiseSuppressor?>>()

    @Volatile private var lastActive = false
    @Volatile private var lastReason = "init"
    @Volatile private var lastTs = 0L

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookAudioRecordCtor()
        hookAudioRecordBuilder(lpparam)
        hookStartRecording()
        hookReadShort()
        hookReadByte()
        hookReadByteBuffer()
        hookRelease()
    }

    // ===== Indicador: emisión y respuesta =====
    private fun emitStatus(active: Boolean, reason: String, ar: AudioRecord?, sid: Int) {
        lastActive = active
        lastReason = reason
        lastTs = System.currentTimeMillis()
        try {
            AppCtx.get()?.sendBroadcast(
                Intent("com.d4vram.whatsmicfix.WFM_STATUS")
                    .setPackage(APP_PKG) // ← explícito a tu app
                    .putExtra("active", active)
                    .putExtra("reason", reason)
                    .putExtra("sid", sid)
                    .putExtra("sr", try { ar?.sampleRate ?: -1 } catch (_: Throwable) { -1 })
                    .putExtra("enc", try { ar?.audioFormat ?: -1 } catch (_: Throwable) { -1 })
                    .putExtra("ts", lastTs)
            )
        } catch (_: Throwable) {}
    }

    fun respondPing() {
        try {
            AppCtx.get()?.sendBroadcast(
                Intent("com.d4vram.whatsmicfix.WFM_STATUS")
                    .setPackage(APP_PKG)
                    .putExtra("active", lastActive)
                    .putExtra("reason", lastReason)
                    .putExtra("ts", lastTs)
            )
        } catch (_: Throwable) {}
    }

    // ===== Constructors =====
    private fun hookAudioRecordCtor() {
        XposedHelpers.findAndHookConstructor(
            AudioRecord::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    Prefs.reloadIfStale(500)
                    if (!Prefs.moduleEnabled) return

                    val origSr  = p.args[1] as Int
                    val origCh  = p.args[2] as Int
                    val origFmt = p.args[3] as Int

                    if (Prefs.forceSourceMic) p.args[0] = MediaRecorder.AudioSource.MIC

                    if (!Prefs.respectAppFormat) {
                        val sr = pickSampleRate(origSr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        p.args[1] = sr
                        p.args[2] = AudioFormat.CHANNEL_IN_MONO
                        p.args[3] = AudioFormat.ENCODING_PCM_16BIT
                    } else {
                        if (origSr <= 0 || AudioRecord.getMinBufferSize(origSr, origCh, origFmt) <= 0) {
                            p.args[1] = SR_FALLBACK
                        }
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    val ar = p.thisObject as AudioRecord
                    val enc = try { ar.audioFormat } catch (_: Throwable) { AudioFormat.ENCODING_INVALID }
                    isPcm16[ar] = (enc == AudioFormat.ENCODING_PCM_16BIT)

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

    private fun pickSampleRate(requested: Int, channels: Int, format: Int): Int {
        val first = if (requested > 0) requested else if (Build.VERSION.SDK_INT >= 30) SR_HIGH else SR_FALLBACK
        val ok1 = AudioRecord.getMinBufferSize(first, channels, format) > 0
        return if (ok1) first else SR_FALLBACK
    }

    // ===== Builder =====
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

    // ===== startRecording / release =====
    private fun hookStartRecording() {
        fun sendDiag(msg: String) {
            try {
                AppCtx.get()?.sendBroadcast(
                    Intent("com.d4vram.whatsmicfix.DIAG_EVENT")
                        .setPackage(APP_PKG)
                        .putExtra("msg", msg)
                )
            } catch (_: Throwable) {}
        }

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

                sendDiag("startRecording: sid=$sid, AGC=${fxBySession[sid]?.first!=null}, NS=${fxBySession[sid]?.second!=null}, sr=${try { ar.sampleRate } catch (_: Throwable) { -1 }}, enc=${try { ar.audioFormat } catch (_: Throwable) { -1 }}")

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
                emitStatus(false, "release", ar, sid)
            }
        })
    }

    // ===== read(...) con pre-boost =====
    private fun hookReadShort() {
        XposedHelpers.findAndHookMethod(
            AudioRecord::class.java, "read",
            ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    Prefs.reloadIfStale(500)
                    if (!Prefs.moduleEnabled || !Prefs.enablePreboost) return
                    val count = (p.result as? Int) ?: return
                    if (count <= 0) return

                    val ar = p.thisObject as AudioRecord
                    if (isPcm16[ar] != true) return

                    val buf = p.args[0] as ShortArray
                    val off = p.args[1] as Int
                    val len = min(count, buf.size - off)
                    if (len <= 0) return

                    preboostShorts(buf, off, len, Prefs.boostFactor)
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
                    Prefs.reloadIfStale(500)
                    if (!Prefs.moduleEnabled || !Prefs.enablePreboost) return
                    val count = (p.result as? Int) ?: return
                    if (count <= 0) return

                    val ar = p.thisObject as AudioRecord
                    if (isPcm16[ar] != true) return

                    val buf = p.args[0] as ByteArray
                    val off = p.args[1] as Int
                    val len = min(count, buf.size - off)
                    if (len <= 1) return

                    preboostPcm16LeBytes(buf, off, len, Prefs.boostFactor)
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
                        Prefs.reloadIfStale(500)
                        if (!Prefs.moduleEnabled || !Prefs.enablePreboost) return
                        val count = (p.result as? Int) ?: return
                        if (count <= 0) return

                        val ar = p.thisObject as AudioRecord
                        if (isPcm16[ar] != true) return

                        val bb = p.args[0] as ByteBuffer
                        val valid = min(count, bb.remaining())
                        if (valid <= 1) return

                        preboostPcm16LeByteBuffer(bb, valid, Prefs.boostFactor)
                        val sid = try { ar.audioSessionId } catch (_: Throwable) { -1 }
                        emitStatus(true, "preboost", ar, sid)
                    }
                }
            )
        } catch (t: Throwable) {
            Logx.e("No se pudo hookear read(ByteBuffer,...)", t)
        }
    }

    // ===== helpers =====
    private fun clamp16(x: Int): Short {
        return when {
            x > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
            x < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
            else -> x.toShort()
        }
    }

    private fun preboostShorts(buf: ShortArray, off: Int, len: Int, factor: Float) {
        val q = (factor * 1024f + 0.5f).toInt()
        val end = off + len
        var i = off
        while (i < end) {
            val s = buf[i].toInt()
            val boosted = (s * q) shr 10
            buf[i] = clamp16(boosted)
            i++
        }
    }

    private fun preboostPcm16LeBytes(buf: ByteArray, off: Int, len: Int, factor: Float) {
        val q = (factor * 1024f + 0.5f).toInt()
        val end = off + len - 1
        var i = off
        while (i < end) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            val boosted = (s * q) shr 10
            val cl = clamp16(boosted).toInt()
            buf[i]     = (cl and 0xFF).toByte()
            buf[i + 1] = ((cl shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun preboostPcm16LeByteBuffer(bb: ByteBuffer, validBytes: Int, factor: Float) {
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
