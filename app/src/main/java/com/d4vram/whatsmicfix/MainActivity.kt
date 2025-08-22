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
    private lateinit var swAdvanced: Switch
    private lateinit var tvFactor: TextView
    private lateinit var tvRangeInfo: TextView
    private lateinit var seekBoost: SeekBar

    // Mapeo del slider: 0..200 → BASIC_MIN_DB..currentMaxDb
    private var currentMaxDb = Prefs.BASIC_MAX_DB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        swEnable   = findViewById(R.id.swEnable)
        swAdvanced = findViewById(R.id.swAdvanced)
        tvFactor   = findViewById(R.id.tvFactor)
        tvRangeInfo= findViewById(R.id.tvRangeInfo)
        seekBoost  = findViewById(R.id.seekBoost)

        seekBoost.max = 200
        if (Build.VERSION.SDK_INT >= 26) seekBoost.min = 0

        val sp = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val enabled  = sp.getBoolean(Prefs.KEY_ENABLE, true)
        val adv      = sp.getBoolean(Prefs.KEY_ADV, false)
        currentMaxDb = if (adv) Prefs.ADV_MAX_DB else Prefs.BASIC_MAX_DB
        val savedDb  = sp.getFloat(Prefs.KEY_DB, 6f).coerceIn(Prefs.BASIC_MIN_DB, currentMaxDb)

        swEnable.isChecked = enabled
        swAdvanced.isChecked = adv
        seekBoost.progress = dbToProgress(savedDb, currentMaxDb)
        updateRangeInfo()
        updateLabel(enabled, savedDb)

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            val db = progressToDb(seekBoost.progress, currentMaxDb)
            save(isChecked, db, swAdvanced.isChecked)
            updateLabel(isChecked, db)
        }

        swAdvanced.setOnCheckedChangeListener { _, isAdv ->
            currentMaxDb = if (isAdv) Prefs.ADV_MAX_DB else Prefs.BASIC_MAX_DB
            val db = progressToDb(seekBoost.progress, currentMaxDb).coerceIn(Prefs.BASIC_MIN_DB, currentMaxDb)
            seekBoost.progress = dbToProgress(db, currentMaxDb)
            save(swEnable.isChecked, db, isAdv)
            updateRangeInfo()
            updateLabel(swEnable.isChecked, db)
        }

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val db = progressToDb(p, currentMaxDb)
                save(swEnable.isChecked, db, swAdvanced.isChecked)
                updateLabel(swEnable.isChecked, db)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        makePrefsWorldReadable()
    }

    private fun save(enabled: Boolean, db: Float, adv: Boolean) {
        Prefs.saveFromUi(this, enabled, db, adv)
        makePrefsWorldReadable()
    }

    private fun makePrefsWorldReadable() {
        try {
            File(applicationInfo.dataDir + "/shared_prefs/${Prefs.FILE}.xml")
                .setReadable(true, false)
        } catch (_: Throwable) {}
    }

    private fun updateRangeInfo() {
        val minX = dbToFactor(Prefs.BASIC_MIN_DB)
        val maxX = dbToFactor(currentMaxDb)
        val advText = if (swAdvanced.isChecked) " (avanzado)" else ""
        tvRangeInfo.text = "Rango: x%.1f … x%.1f%s  (si distorsiona, baja el deslizador)"
            .format(minX, maxX, advText)
    }

    private fun updateLabel(enabled: Boolean, db: Float) {
        val x = dbToFactor(db)
        val dbStr = if (db >= 0f) "+%.1f dB".format(db) else "%.1f dB".format(db)
        tvFactor.text = if (enabled) "Ganancia: x%.2f (%s)".format(x, dbStr) else "Ganancia desactivada"
    }

    // ===== mapeos =====
    private fun dbToFactor(db: Float) = 10f.pow(db / 20f)
    private fun progressToDb(p: Int, maxDb: Float): Float {
        val t = (p / 200f) // 0..1
        return Prefs.BASIC_MIN_DB + t * (maxDb - Prefs.BASIC_MIN_DB)
    }
    private fun dbToProgress(db: Float, maxDb: Float): Int {
        val t = (db - Prefs.BASIC_MIN_DB) / (maxDb - Prefs.BASIC_MIN_DB)
        return (t * 200f).toInt().coerceIn(0, 200)
    }
}
