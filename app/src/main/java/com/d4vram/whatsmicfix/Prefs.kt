package com.d4vram.whatsmicfix

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

private const val MODULE_PKG = "com.d4vram.whatsmicfix"

object Prefs {

    const val FILE = "whatsmicfix_prefs"

    const val KEY_ENABLE          = "module_enabled"
    const val KEY_ADVANCED        = "advanced_mode"
    const val KEY_FACTOR          = "boost_db"
    const val KEY_FORCE_SOURCE    = "force_source_mic"
    const val KEY_ENABLE_AGC      = "enable_agc"
    const val KEY_ENABLE_NS       = "enable_ns"
    const val KEY_RESPECT_APP_FMT = "respect_app_format"
    const val KEY_ENABLE_PREBOOST = "enable_preboost"

    @Volatile var moduleEnabled: Boolean = true
    @Volatile var advancedMode: Boolean = false
    @Volatile var boostDb: Float = 8.0f
    @Volatile var boostFactor: Float = 2.51f
    @Volatile var forceSourceMic: Boolean = false
    @Volatile var enableAgc: Boolean = true
    @Volatile var enableNs: Boolean = true
    @Volatile var respectAppFormat: Boolean = true
    @Volatile var enablePreboost: Boolean = true

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun dbToLinear(db: Float): Float = 10.0f.pow(db / 20f)

    private var xsp: XSharedPreferences? = null
    private val lastReloadNs = AtomicLong(0L)

    /** NUEVO: detectar primera ejecución real (sin pisar prefs). */
    fun prefsFileExists(ctx: Context): Boolean {
    val f = java.io.File(ctx.applicationInfo.dataDir + "/shared_prefs/${Prefs.FILE}.xml")
    return f.exists()
}


    fun makePrefsWorldReadable(ctx: Context) {
        try {
            val paths = listOf(
                ctx.getDatabasePath("../shared_prefs/$FILE.xml"),
                java.io.File(ctx.applicationInfo.dataDir + "/shared_prefs/$FILE.xml"),
                java.io.File("/data/data/$MODULE_PKG/shared_prefs/$FILE.xml")
            )
            for (f in paths) {
                if (f.exists()) {
                    f.setReadable(true, false)
                    f.setExecutable(false, false)
                    f.parentFile?.apply {
                        setReadable(true, false)
                        setExecutable(true, false)
                    }
                }
            }
        } catch (t: Throwable) {
            Logx.w("Error haciendo prefs world readable: ${t.message}")
        }
    }

    fun forceReload() {
        lastReloadNs.set(0L)
        xsp = null
    }

    fun reloadIfStale(ttlMs: Long = 500) {
        AppCtx.get()?.let { app ->
            if (app.packageName == MODULE_PKG) {
                loadFromSp(app); return
            }
        }
        loadFromXsp(ttlMs)
    }

    fun reloadIfStale(ctx: Context) = loadFromSp(ctx)
    fun reload(ctx: Context) = loadFromSp(ctx)
    fun reload() = reloadIfStale(500)

    fun saveFromUi(ctx: Context, enable: Boolean, db: Float, adv: Boolean) {
        val limitedDb = db.coerceIn(-6f, 12f)
        sp(ctx).edit()
            .putBoolean(KEY_ENABLE, enable)
            .putBoolean(KEY_ADVANCED, adv)
            .putFloat(KEY_FACTOR, limitedDb)
            .apply()

        moduleEnabled = enable
        advancedMode  = adv
        boostDb       = limitedDb
        boostFactor   = dbToLinear(limitedDb)

        makePrefsWorldReadable(ctx)
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

        makePrefsWorldReadable(ctx)
    }

    private fun loadFromSp(ctx: Context) {
        val s = sp(ctx)
        moduleEnabled    = s.getBoolean(KEY_ENABLE, true)
        advancedMode     = s.getBoolean(KEY_ADVANCED, false)
        boostDb          = s.getFloat(KEY_FACTOR, 8.0f).coerceIn(-6f, 12f)
        boostFactor      = dbToLinear(boostDb)
        forceSourceMic   = s.getBoolean(KEY_FORCE_SOURCE, false)
        enableAgc        = s.getBoolean(KEY_ENABLE_AGC, true)
        enableNs         = s.getBoolean(KEY_ENABLE_NS, true)
        respectAppFormat = s.getBoolean(KEY_RESPECT_APP_FMT, true)
        enablePreboost   = s.getBoolean(KEY_ENABLE_PREBOOST, true)
    }

    private fun loadFromXsp(ttlMs: Long) {
        val now = System.nanoTime()
        val last = lastReloadNs.get()
        if (now - last < ttlMs * 1_000_000L) return
        lastReloadNs.set(now)

        try {
            val prefs = xsp ?: XSharedPreferences(MODULE_PKG, FILE).also {
                it.makeWorldReadable(); xsp = it
            }
            prefs.reload()

            moduleEnabled    = prefs.getBoolean(KEY_ENABLE, true)
            advancedMode     = prefs.getBoolean(KEY_ADVANCED, false)
            boostDb          = try {
                prefs.getFloat(KEY_FACTOR, 8.0f).coerceIn(-6f, 12f)
            } catch (_: Throwable) {
                prefs.getString(KEY_FACTOR, "8.0")?.toFloatOrNull()?.coerceIn(-6f, 12f) ?: 8.0f
            }
            boostFactor      = dbToLinear(boostDb)
            forceSourceMic   = prefs.getBoolean(KEY_FORCE_SOURCE, false)
            enableAgc        = prefs.getBoolean(KEY_ENABLE_AGC, true)
            enableNs         = prefs.getBoolean(KEY_ENABLE_NS, true)
            respectAppFormat = prefs.getBoolean(KEY_RESPECT_APP_FMT, true)
            enablePreboost   = prefs.getBoolean(KEY_ENABLE_PREBOOST, true)

        } catch (t: Throwable) {
            Logx.e("Error XSharedPreferences, usando defaults", t)
            moduleEnabled = true
            boostDb = 8.0f
            boostFactor = dbToLinear(boostDb)
            enablePreboost = true
        }
    }
}
