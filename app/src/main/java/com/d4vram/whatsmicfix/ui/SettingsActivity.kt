package com.d4vram.whatsmicfix.ui

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.d4vram.whatsmicfix.Prefs
import com.d4vram.whatsmicfix.R
import kotlin.math.log10

class SettingsActivity : AppCompatActivity() {

    private fun toFactor(progress: Int): Float {
        // 50..250  -> 0.5x .. 2.5x
        return progress / 100f
    }

    private fun toProgress(factor: Float): Int {
        return (factor * 100).toInt().coerceIn(50, 250)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val swEnable = findViewById<Switch>(R.id.swEnable)
        val tvFactor = findViewById<TextView>(R.id.tvFactor)
        val seek = findViewById<SeekBar>(R.id.seekBoost)

        // Cargar valores actuales
        val sp = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val enabled = sp.getBoolean(Prefs.KEY_ENABLE_PREBOOST, true)
        val factor = sp.getFloat(Prefs.KEY_BOOST_FACTOR, 1.35f)

        swEnable.isChecked = enabled
        seek.max = 250
        seek.min = 50
        seek.progress = toProgress(factor)
        tvFactor.text = factorLabel(enabled, factor)

        // Guardar en caliente al cambiar
        fun persist() {
            val f = toFactor(seek.progress)
            Prefs.saveFromUi(this, swEnable.isChecked, f)
            tvFactor.text = factorLabel(swEnable.isChecked, f)
        }

        swEnable.setOnCheckedChangeListener { _, _ -> persist() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { persist() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun factorLabel(enabled: Boolean, factor: Float): String {
        val db = 20 * log10(factor.toDouble())
        val dbStr = if (db >= 0) "+%.1f dB".format(db) else "%.1f dB".format(db)
        return if (enabled) "Ganancia: x%.2f  (%s)".format(factor, dbStr)
        else "Ganancia desactivada"
    }
}
