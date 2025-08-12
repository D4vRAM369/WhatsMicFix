package com.d4vram.whatsmicfix

import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.math.log10

class MainActivity : AppCompatActivity() {

    private lateinit var swEnable: Switch
    private lateinit var tvFactor: TextView
    private lateinit var seekBoost: SeekBar

    private val PREFS_NAME = "whatsmicfix_prefs"
    private val KEY_ENABLE = "enable_preboost"
    private val KEY_FACTOR = "boost_factor" // guardamos 0.5..2.5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        swEnable = findViewById(R.id.swEnable)
        tvFactor = findViewById(R.id.tvFactor)
        seekBoost = findViewById(R.id.seekBoost)

        // Configurar rango 0.5×..2.5× => 50..250
        seekBoost.max = 250
        if (Build.VERSION.SDK_INT >= 26) {
            seekBoost.min = 50
        }

        // Cargar valores guardados
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = sp.getBoolean(KEY_ENABLE, true)
        val factor = sp.getFloat(KEY_FACTOR, 1.35f).coerceIn(0.5f, 2.5f)

        swEnable.isChecked = enabled
        seekBoost.progress = (factor * 100).toInt().coerceIn(50, 250)
        updateFactorLabel(enabled, factor)

        // Listeners
        swEnable.setOnCheckedChangeListener { _, isChecked ->
            val f = progressToFactor(seekBoost.progress)
            savePrefs(isChecked, f)
            updateFactorLabel(isChecked, f)
        }

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val f = progressToFactor(progress)
                savePrefs(swEnable.isChecked, f)
                updateFactorLabel(swEnable.isChecked, f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Asegura permisos de lectura la primera vez que abres la app
        makePrefsWorldReadable()
    }

    private fun progressToFactor(progress: Int): Float {
        // progress 50..250 -> 0.5..2.5
        val p = progress.coerceIn(50, 250)
        return p / 100f
    }

    private fun savePrefs(enabled: Boolean, factor: Float) {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sp.edit()
            .putBoolean(KEY_ENABLE, enabled)
            .putFloat(KEY_FACTOR, factor.coerceIn(0.5f, 2.5f))
            .apply()
        makePrefsWorldReadable()
    }

    private fun makePrefsWorldReadable() {
        try {
            val f = File(applicationInfo.dataDir + "/shared_prefs/$PREFS_NAME.xml")
            if (f.exists()) f.setReadable(true, false)
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun updateFactorLabel(enabled: Boolean, factor: Float) {
        val db = 20 * log10(factor.toDouble())
        val dbStr = if (db >= 0) "+%.1f dB".format(db) else "%.1f dB".format(db)
        tvFactor.text = if (enabled) {
            "Ganancia: x%.2f ($dbStr)".format(factor)
        } else {
            "Ganancia desactivada"
        }
    }
}
