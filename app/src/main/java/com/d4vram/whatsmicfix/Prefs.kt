package com.d4vram.whatsmicfix

import android.content.Context
import de.robv.android.xposed.XSharedPreferences
import java.io.File

object Prefs {
    private const val PKG = "com.d4vram.whatsmicfix"
    const val FILE = "whatsmicfix_prefs"
    const val KEY_ENABLE = "enable_preboost"
    const val KEY_FACTOR = "boost_factor"

    @Volatile var enablePreboost: Boolean = true
        private set
    @Volatile var boostFactor: Float = 1.35f
        private set

    fun reload() {
        try {
            val xp = XSharedPreferences(PKG, FILE)
            xp.makeWorldReadable()
            xp.reload()
            enablePreboost = xp.getBoolean(KEY_ENABLE, true)
            boostFactor = xp.getFloat(KEY_FACTOR, 1.35f).coerceIn(0.5f, 2.5f)
            Logx.d("Prefs reloaded: enable=$enablePreboost factor=$boostFactor")
        } catch (t: Throwable) {
            Logx.e("Prefs reload error", t)
        }
    }

    /** (Opcional) si guardas desde una Activity propia */
    fun saveFromUi(ctx: Context, enable: Boolean, factor: Float) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLE, enable)
            .putFloat(KEY_FACTOR, factor.coerceIn(0.5f, 2.5f))
            .apply()
        try {
            val f = File(ctx.applicationInfo.dataDir + "/shared_prefs/$FILE.xml")
            if (f.exists()) f.setReadable(true, false)
        } catch (_: Throwable) {}
    }
}
