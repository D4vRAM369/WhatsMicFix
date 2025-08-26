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

    private lateinit var swForceMic: Switch
    private lateinit var swAgc: Switch
    private lateinit var swNs: Switch
    private lateinit var swRespectFmt: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        swEnable = findViewById(R.id.swEnable)
        tvFactor = findViewById(R.id.tvFactor)
        seekBoost = findViewById(R.id.seekBoost)

        swForceMic = findViewById(R.id.swForceMic)
        swAgc = findViewById(R.id.swAgc)
        swNs = findViewById(R.id.swNs)
        swRespectFmt = findViewById(R.id.swRespectFmt)

        // Rango 0.5×..2.5×  => progress 50..250
        seekBoost.max = 250
        if (Build.VERSION.SDK_INT >= 26) seekBoost.min = 50

        val sp = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val enabled = sp.getBoolean(Prefs.KEY_ENABLE, true)
        val factor = sp.getFloat(Prefs.KEY_FACTOR, 1.35f).coerceIn(0.5f, 2.5f)
        val forceMic = sp.getBoolean(Prefs.KEY_FORCE_SOURCE, false)
        val agc = sp.getBoolean(Prefs.KEY_ENABLE_AGC, true)
        val ns = sp.getBoolean(Prefs.KEY_ENABLE_NS, true)
        val respect = sp.getBoolean(Prefs.KEY_RESPECT_APP_FMT, true)

        swEnable.isChecked = enabled
        seekBoost.progress = (factor * 100).toInt().coerceIn(50, 250)
        swForceMic.isChecked = forceMic
        swAgc.isChecked = agc
        swNs.isChecked = ns
        swRespectFmt.isChecked = respect

        updateLabel(enabled, factor)

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            saveAndUpdate(isChecked, progressToFactor(seekBoost.progress))
        }
        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                saveAndUpdate(swEnable.isChecked, progressToFactor(p))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val toggleListener = { _: Any, _: Boolean ->
            saveAndUpdate(swEnable.isChecked, progressToFactor(seekBoost.progress))
        }
        swForceMic.setOnCheckedChangeListener(toggleListener)
        swAgc.setOnCheckedChangeListener(toggleListener)
        swNs.setOnCheckedChangeListener(toggleListener)
        swRespectFmt.setOnCheckedChangeListener(toggleListener)

        makePrefsWorldReadable()
    }

    private fun progressToFactor(p: Int) = p.coerceIn(50, 250) / 100f

    private fun saveAndUpdate(enabled: Boolean, factor: Float) {
        Prefs.saveFromUi(
            this,
            enabled,
            factor,
            swForceMic.isChecked,
            swAgc.isChecked,
            swNs.isChecked,
            swRespectFmt.isChecked
        )
        makePrefsWorldReadable()
        updateLabel(enabled, factor)
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
