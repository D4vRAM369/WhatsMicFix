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
import kotlin.math.pow

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

        // Cargar estado desde preferencias al arrancar (v1.2)
        Prefs.reloadIfStale(this)

        swEnable = findViewById(R.id.swEnable)
        tvFactor = findViewById(R.id.tvFactor)
        seekBoost = findViewById(R.id.seekBoost)

        swForceMic = findViewById(R.id.swForceMic)
        swAgc = findViewById(R.id.swAgc)
        swNs = findViewById(R.id.swNs)
        swRespectFmt = findViewById(R.id.swRespectFmt)

        // Rango 0.5×..2.5×  => progress 50..250
        seekBoost.max = 250
        if (Build.VERSION.SDK_INT >= 26) {
            seekBoost.min = 50
        }

        // Estado inicial desde Prefs (v1.2 guarda dB; convertimos a factor lineal para la UI)
        val enabled = Prefs.moduleEnabled
        val factor = dbToLinear(Prefs.boostDb).coerceIn(0.5f, 2.5f)
        val forceMic = Prefs.forceSourceMic
        val agc = Prefs.enableAgc
        val ns = Prefs.enableNs
        val respect = Prefs.respectAppFormat

        swEnable.isChecked = enabled
        seekBoost.progress = (factor * 100).toInt().coerceIn(50, 250)
        swForceMic.isChecked = forceMic
        swAgc.isChecked = agc
        swNs.isChecked = ns
        swRespectFmt.isChecked = respect

        updateLabel(enabled, factor)

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            saveAll(isChecked, progressToFactor(seekBoost.progress))
        }

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    saveAll(swEnable.isChecked, progressToFactor(p))
                } else {
                    updateLabel(swEnable.isChecked, progressToFactor(p))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val toggleListener = { _: Any, _: Boolean ->
            saveAll(swEnable.isChecked, progressToFactor(seekBoost.progress))
        }
        swForceMic.setOnCheckedChangeListener(toggleListener)
        swAgc.setOnCheckedChangeListener(toggleListener)
        swNs.setOnCheckedChangeListener(toggleListener)
        swRespectFmt.setOnCheckedChangeListener(toggleListener)

        makePrefsWorldReadable()
    }

    private fun progressToFactor(p: Int) = p.coerceIn(50, 250) / 100f

    // Convierte dB ↔ factor (la UI trabaja en factor; Prefs v1.2 guarda dB)
    private fun factorToDb(f: Float): Float = (20f * log10(f.toDouble())).toFloat()
    private fun dbToLinear(db: Float): Float = 10.0f.pow(db / 20f)

    private fun saveAll(enabled: Boolean, factor: Float) {
        val db = factorToDb(factor)
        // Modo avanzado: lo marcamos activo si cualquier toggle avanzado está activado
        val adv = swForceMic.isChecked || !swRespectFmt.isChecked || !swAgc.isChecked || !swNs.isChecked

        // v1.2 — guarda la parte básica (enable + dB + adv)
        Prefs.saveFromUi(
            this,
            enabled,
            db,
            adv
        )

        // v1.2 — guarda los toggles avanzados por separado
        Prefs.saveAdvancedToggles(
            this,
            forceMic = swForceMic.isChecked,
            agc = swAgc.isChecked,
            ns = swNs.isChecked,
            respectFmt = swRespectFmt.isChecked
        )

        makePrefsWorldReadable()
        updateLabel(enabled, factor)
    }

    private fun makePrefsWorldReadable() {
        try {
            // Asegúrate de que Prefs.FILE exista y coincida con el nombre real del SharedPreferences (v1.2: "whatsmicfix_prefs")
            File(applicationInfo.dataDir + "/shared_prefs/${Prefs.FILE}.xml")
                .setReadable(true, false)
        } catch (_: Throwable) {
            // ignora
        }
    }

    private fun updateLabel(enabled: Boolean, factor: Float) {
        val db = 20 * log10(factor.toDouble())
        val dbStr = if (db >= 0) "+%.1f dB".format(db) else "%.1f dB".format(db)
        tvFactor.text = if (enabled) "Ganancia: x%.2f ($dbStr)".format(factor)
        else "Ganancia desactivada"
    }
}
