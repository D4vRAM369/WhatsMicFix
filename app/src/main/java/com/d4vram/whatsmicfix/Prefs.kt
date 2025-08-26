package com.d4vram.whatsmicfix

import android.content.Context
import de.robv.android.xposed.XSharedPreferences
import java.io.File
import kotlin.math.pow

object Prefs {
    private const val PKG = "com.d4vram.whatsmicfix.v1.2"
    const val FILE = "whatsmicfix_prefs"

    const val KEY_ENABLE = "enable_preboost"
    const val KEY_DB = "boost_db"                  // guardamos en dB
    const val KEY_ADV = "advanced_range"           // on/off rango avanzado

    // Rango en dB
    const val BASIC_MIN_DB = -6f   // ≈0.5×
    const val BASIC_MAX_DB =  +8f  // ≈2.5×
    const val ADV_MAX_DB   = +12f  // ≈4×

    // Valores efectivos en el proceso hookeado
    @Volatile var enablePreboost: Boolean = true
        private set
    @Volatile var boostDb: Float = 6f
        private set
    @Volatile var advanced: Boolean = false
        private set

    fun reload() {
        try {
            val xp = XSharedPreferences(PKG, FILE)
            xp.makeWorldReadable()
            xp.reload()

            enablePreboost = xp.getBoolean(KEY_ENABLE, true)
            val savedDb = xp.getFloat(KEY_DB, 6f)
            advanced = xp.getBoolean(KEY_ADV, false)
            boostDb = savedDb.coerceIn(
                BASIC_MIN_DB,
                if (advanced) ADV_MAX_DB else BASIC_MAX_DB
            )

            Logx.d("Prefs reloaded: enable=$enablePreboost db=$boostDb adv=$advanced")
        } catch (t: Throwable) {
            Logx.e("Prefs reload error", t)
        }
    }

    /** dB → factor lineal */
    fun dbToFactor(db: Float): Float = 10f.pow(db / 20f)

    /** Guardado desde la UI */
    fun saveFromUi(ctx: Context, enable: Boolean, db: Float, adv: Boolean) {
        val maxDb = if (adv) ADV_MAX_DB else BASIC_MAX_DB
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLE, enable)
            .putFloat(KEY_DB, db.coerceIn(BASIC_MIN_DB, maxDb))
            .putBoolean(KEY_ADV, adv)
            .apply()

        try {
            val f = File(ctx.applicationInfo.dataDir + "/shared_prefs/$FILE.xml")
            if (f.exists()) f.setReadable(true, false)
        } catch (_: Throwable) {}
    }
}
