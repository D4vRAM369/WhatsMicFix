package com.d4vram.whatsmicfix

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

// Evitamos BuildConfig: paquete del módulo para XSharedPreferences
private const val MODULE_PKG = "com.d4vram.whatsmicfix"

object Prefs {

    // ===== Archivo y claves =====
    const val FILE = "whatsmicfix_prefs"

    const val KEY_ENABLE          = "module_enabled"
    const val KEY_ADVANCED        = "advanced_mode"
    const val KEY_FACTOR          = "boost_db"                // dB (no factor)
    const val KEY_FORCE_SOURCE    = "force_source_mic"
    const val KEY_ENABLE_AGC      = "enable_agc"
    const val KEY_ENABLE_NS       = "enable_ns"
    const val KEY_RESPECT_APP_FMT = "respect_app_format"
    const val KEY_ENABLE_PREBOOST = "enable_preboost"

    // ===== Estado en memoria =====
    @Volatile var moduleEnabled: Boolean = true
    @Volatile var advancedMode: Boolean = false
    @Volatile var boostDb: Float = 0f
    @Volatile var boostFactor: Float = 1f
    @Volatile var forceSourceMic: Boolean = false
    @Volatile var enableAgc: Boolean = true
    @Volatile var enableNs: Boolean = true
    @Volatile var respectAppFormat: Boolean = true
    @Volatile var enablePreboost: Boolean = false

    // ===== Internos =====
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun dbToLinear(db: Float): Float = 10.0f.pow(db / 20f)

    // cache XSP con TTL para no machacar I/O
    private var xsp: XSharedPreferences? = null
    private val lastReloadNs = AtomicLong(0L)

    /** Llamar desde UI tras cada cambio para que WA pueda leer el XML (0644). */
    fun makePrefsWorldReadable(ctx: Context) {
        try {
            val f = ctx.getDatabasePath("../shared_prefs/$FILE.xml")
            if (f.exists()) {
                f.setReadable(true, false)   // -rw-r--r--
                f.setExecutable(false, false)
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    /** Forzar que el próximo reload ignore el TTL (usado por broadcast RELOAD). */
    fun forceReload() { lastReloadNs.set(0L) }

    /** Recarga con TTL. Si estamos en el módulo, usa SP normales; si no, XSharedPreferences. */
    fun reloadIfStale(ttlMs: Long = 500) {
        AppCtx.get()?.let { app ->
            if (app.packageName == MODULE_PKG) {
                loadFromSp(app)
                return
            }
        }
        loadFromXsp(ttlMs)
    }

    /** Compatibilidad con tu UI actual (llama desde Activity). */
    fun reloadIfStale(ctx: Context) = loadFromSp(ctx)
    fun reload(ctx: Context) = loadFromSp(ctx)
    fun reload() = reloadIfStale(500)

    /** Guardados desde UI (v1.2 mantiene esta firma). */
    fun saveFromUi(ctx: Context, enable: Boolean, db: Float, adv: Boolean) {
        sp(ctx).edit()
            .putBoolean(KEY_ENABLE, enable)
            .putBoolean(KEY_ADVANCED, adv)
            .putFloat(KEY_FACTOR, db)
            .apply()
        moduleEnabled = enable
        advancedMode  = adv
        boostDb       = db
        boostFactor   = dbToLinear(db)
    }

    fun saveAdvancedToggles(
        ctx: Context,
        forceMic: Boolean = forceSourceMic,
        agc: Boolean = enableAgc,
        ns: Boolean = enableNs,
        respectFmt: Boolean = respectAppFormat,
        preboost: Boolean = enablePreboost
    ) {
        sp(ctx).edit()
            .putBoolean(KEY_FORCE_SOURCE, forceMic)
            .putBoolean(KEY_ENABLE_AGC, agc)
            .putBoolean(KEY_ENABLE_NS, ns)
            .putBoolean(KEY_RESPECT_APP_FMT, respectFmt)
            .putBoolean(KEY_ENABLE_PREBOOST, preboost)
            .apply()
        forceSourceMic   = forceMic
        enableAgc        = agc
        enableNs         = ns
        respectAppFormat = respectFmt
        enablePreboost   = preboost
    }

    // ===== Carga interna =====

    private fun loadFromSp(ctx: Context) {
        val s = sp(ctx)
        moduleEnabled    = s.getBoolean(KEY_ENABLE, moduleEnabled)
        advancedMode     = s.getBoolean(KEY_ADVANCED, advancedMode)
        boostDb          = s.getFloat(KEY_FACTOR, boostDb)
        boostFactor      = dbToLinear(boostDb)
        forceSourceMic   = s.getBoolean(KEY_FORCE_SOURCE, forceSourceMic)
        enableAgc        = s.getBoolean(KEY_ENABLE_AGC, enableAgc)
        enableNs         = s.getBoolean(KEY_ENABLE_NS, enableNs)
        respectAppFormat = s.getBoolean(KEY_RESPECT_APP_FMT, respectAppFormat)
        enablePreboost   = s.getBoolean(KEY_ENABLE_PREBOOST, enablePreboost)
    }

    private fun loadFromXsp(ttlMs: Long) {
        val now = System.nanoTime()
        val last = lastReloadNs.get()
        if (now - last < ttlMs * 1_000_000L) return
        lastReloadNs.set(now)

        val prefs = (xsp ?: XSharedPreferences(MODULE_PKG, FILE)).also {
            it.makeWorldReadable()
            it.reload()
            xsp = it
        }

        moduleEnabled    = prefs.getBoolean(KEY_ENABLE, moduleEnabled)
        advancedMode     = prefs.getBoolean(KEY_ADVANCED, advancedMode)
        boostDb          = try { prefs.getFloat(KEY_FACTOR, boostDb) } catch (_: Throwable) {
            prefs.getString(KEY_FACTOR, null)?.toFloatOrNull() ?: boostDb
        }
        boostFactor      = dbToLinear(boostDb)
        forceSourceMic   = prefs.getBoolean(KEY_FORCE_SOURCE, forceSourceMic)
        enableAgc        = prefs.getBoolean(KEY_ENABLE_AGC, enableAgc)
        enableNs         = prefs.getBoolean(KEY_ENABLE_NS, enableNs)
        respectAppFormat = prefs.getBoolean(KEY_RESPECT_APP_FMT, respectAppFormat)
        enablePreboost   = prefs.getBoolean(KEY_ENABLE_PREBOOST, enablePreboost)
    }
}
