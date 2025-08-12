package com.d4vram.whatsmicfix

import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.d4vram.whatsmicfix.R
import java.io.File
import kotlin.math.log10

class MainActivity : AppCompatActivity() {

    private lateinit var swEnable: Switch
    private lateinit var tvFactor: TextView
    private lateinit var seekBoost: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings) // <-- usa tu layout existente

        swEnable = findViewById(R.id.swEnable)
        tvFactor = findViewById(R.id.tvFactor)
        seekBoost = findViewById(R.id.seekBoost)

        // Rango 0.5×..2.5×  => progress 50..250
        seekBoost.max = 250
        if (Build.VERSION.SDK_INT >= 26) seekBoost.min = 50

        val sp = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val enabled = sp.getBoolean(Prefs.KEY_ENABLE, true)
        val factor = sp.getFloat(Prefs.KEY_FACTOR, 1.35f).coerceIn(0.5f, 2.5f)

        swEnable.isChecked = enabled
        seekBoost.progress = (factor * 100).toInt().coerceIn(50, 250)
        updateLabel(enabled, factor)

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            val f = progressToFactor(seekBoost.progress)
            save(isChecked, f); updateLabel(isChecked, f)
        }

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val f = progressToFactor(p)
                save(swEnable.isChecked, f); updateLabel(swEnable.isChecked, f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        makePrefsWorldReadable()
    }

    private fun progressToFactor(p: Int) = p.coerceIn(50, 250) / 100f

    private fun save(enabled: Boolean, factor: Float) {
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit()
            .putBoolean(Prefs.KEY_ENABLE, enabled)
            .putFloat(Prefs.KEY_FACTOR, factor.coerceIn(0.5f, 2.5f))
            .apply()
        makePrefsWorldReadable()
    }

    private fun makePrefsWorldReadable() {
        try {
            File(applicationInfo.dataDir + "/shared_prefs/${Prefs.FILE}.xml")
                .setReadable(true, false)
        } catch (_: Throwable) {}
    }

    private fun updateLabel(enabled: Boolean, factor: Float) {
        val db = 20 * log10(factor.toDouble())
        val dbStr = if (db >= 0) "+%.1f dB".format(db) else "%.1f dB".format(db)
        tvFactor.text = if (enabled) "Ganancia: x%.2f ($dbStr)".format(factor)
        else "Ganancia desactivada"
    }
}
