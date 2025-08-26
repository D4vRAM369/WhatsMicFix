package com.d4vram.whatsmicfix

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.pow

// Si tienes el XposedBridge API en el classpath, esto permitirá reload() sin pasar Context.
@Suppress("ClassName")
private object __XposedCtx {
    // Cargamos por reflexión para no romper si no está Xposed en compile-time.
    fun appContextOrNull(): Context? = try {
        val cls = Class.forName("de.robv.android.xposed.AndroidAppHelper")
        val m = cls.getMethod("currentApplication")
        val app = m.invoke(null)
        val ctx = app?.javaClass?.getMethod("getApplicationContext")?.invoke(app) as? Context
        ctx
    } catch (_: Throwable) { null }
}

object Prefs {

    // ===== Archivo de SharedPreferences =====
    const val FILE = "whatsmicfix_prefs"

    // ===== Claves principales =====
    const val KEY_ENABLE          = "module_enabled"          // switch global
    const val KEY_ADVANCED        = "advanced_mode"           // modo avanzado (UI)
    const val KEY_FACTOR          = "boost_db"                // valor en dB (no factor)
    const val KEY_FORCE_SOURCE    = "force_source_mic"        // forzar MIC como source
    const val KEY_ENABLE_AGC      = "enable_agc"              // Automatic Gain Control
    const val KEY_ENABLE_NS       = "enable_ns"               // Noise Suppression
    const val KEY_RESPECT_APP_FMT = "respect_app_format"      // respetar formato de la app
    const val KEY_ENABLE_PREBOOST = "enable_preboost"         // aplicar pre-amplificación previa (opcional)

    // ===== Estado en memoria =====
    @Volatile var moduleEnabled: Boolean = true
    @Volatile var advancedMode: Boolean = false

    @Volatile var boostDb: Float = 0f        // dB
    @Volatile var boostFactor: Float = 1f    // lineal = 10^(dB/20)

    @Volatile var forceSourceMic: Boolean = false
    @Volatile var enableAgc: Boolean = true
    @Volatile var enableNs: Boolean = true
    @Volatile var respectAppFormat: Boolean = true
    @Volatile var enablePreboost: Boolean = false

    // ===== Internos =====
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun dbToLinear(db: Float): Float = 10.0f.pow(db / 20f)

    // ===== API v1.2 =====

    /** Carga desde disco a memoria. */
    fun reloadIfStale(ctx: Context) {
        val s = sp(ctx)
        moduleEnabled    = s.getBoolean(KEY_ENABLE, true)
        advancedMode     = s.getBoolean(KEY_ADVANCED, false)

        boostDb          = s.getFloat(KEY_FACTOR, 0f)
        boostFactor      = dbToLinear(boostDb)

        forceSourceMic   = s.getBoolean(KEY_FORCE_SOURCE, false)
        enableAgc        = s.getBoolean(KEY_ENABLE_AGC, true)
        enableNs         = s.getBoolean(KEY_ENABLE_NS, true)
        respectAppFormat = s.getBoolean(KEY_RESPECT_APP_FMT, true)
        enablePreboost   = s.getBoolean(KEY_ENABLE_PREBOOST, false)
    }

    /** Alias por compatibilidad (algunos archivos llaman a `reload`). */
    fun reload(ctx: Context) = reloadIfStale(ctx)

    /** Overload por compatibilidad cuando (mal) pasan un Int en vez de Context. */
    fun reloadIfStale(@Suppress("UNUSED_PARAMETER") dummy: Int) {
        // Intentamos resolver contexto vía Xposed si es posible.
        __XposedCtx.appContextOrNull()?.let { reloadIfStale(it) }
    }

    /** Overload sin parámetros: usa contexto de AndroidAppHelper si existe. */
    fun reload() {
        __XposedCtx.appContextOrNull()?.let { reloadIfStale(it) }
    }

    /** Guarda el básico (enable + dB + adv) y refresca memoria. */
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

    /** Guarda los toggles avanzados. */
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
}
