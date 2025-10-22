package com.d4vram.whatsmicfix

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var btnBuyCoffee: View
    private lateinit var btnGithubSupport: View

    private var distortionWarningShown = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Cargar prefs y NO pisarlas si ya existen
        Prefs.reloadIfStale(this)
        if (!Prefs.prefsFileExists(this)) {
            Prefs.saveFromUi(this, enable = true, db = 8.0f, adv = false)
            Prefs.saveAdvancedToggles(this, preboost = true)
            Prefs.makePrefsWorldReadable(this)
            Prefs.reloadIfStale(this)
        }

        initializeViews()
        loadCurrentSettings()
        setupListeners()
        Prefs.makePrefsWorldReadable(this)
    }

    private fun initializeViews() {
        swEnable     = findViewById(R.id.swEnable)
        tvFactor     = findViewById(R.id.tvFactor)
        seekBoost    = findViewById(R.id.seekBoost)
        swForceMic   = findViewById(R.id.swForceMic)
        swAgc        = findViewById(R.id.swAgc)
        swNs         = findViewById(R.id.swNs)
        swRespectFmt = findViewById(R.id.swRespectFmt)
        btnBuyCoffee = findViewById(R.id.btnBuyCoffee)
        btnGithubSupport = findViewById(R.id.btnGithubSupport)
    }

    private fun setupListeners() {
        // 0.5x..4.0x => 50..400
        seekBoost.max = 400
        if (Build.VERSION.SDK_INT >= 26) seekBoost.min = 50

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            saveAll(isChecked, progressToFactor(seekBoost.progress))
        }

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val factor = progressToFactor(p)
                if (fromUser) {
                    if (factor > 3.0f && !distortionWarningShown) {
                        Toast.makeText(this@MainActivity,
                            "⚠️ Boost muy alto - el compresor evitará distorsión",
                            Toast.LENGTH_SHORT
                        ).show()
                        distortionWarningShown = true
                    }
                    saveAll(swEnable.isChecked, factor)
                } else {
                    updateLabel(swEnable.isChecked, factor)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { distortionWarningShown = false }
        })

        val toggleListener = { _: Any, _: Boolean ->
            saveAll(swEnable.isChecked, progressToFactor(seekBoost.progress))
        }
        swForceMic.setOnCheckedChangeListener(toggleListener)
        swAgc.setOnCheckedChangeListener(toggleListener)
        swNs.setOnCheckedChangeListener(toggleListener)
        swRespectFmt.setOnCheckedChangeListener(toggleListener)

        btnBuyCoffee.setOnClickListener {
            openUrl("https://www.buymeacoffee.com/D4vRAM369")
        }
        btnGithubSupport.setOnClickListener {
            openUrl("https://github.com/D4vRAM369/WhatsMicFix")
        }
    }

    private fun loadCurrentSettings() {
        val enabled = Prefs.moduleEnabled
        val factor  = dbToLinear(Prefs.boostDb).coerceIn(0.5f, 4.0f)

        swEnable.isChecked     = enabled
        seekBoost.progress     = (factor * 100).toInt().coerceIn(50, 400)
        swForceMic.isChecked   = Prefs.forceSourceMic
        swAgc.isChecked        = Prefs.enableAgc
        swNs.isChecked         = Prefs.enableNs
        swRespectFmt.isChecked = Prefs.respectAppFormat

        updateLabel(enabled, factor)
    }

    private fun progressToFactor(p: Int) = p.coerceIn(50, 400) / 100f
    private fun factorToDb(f: Float): Float = (20f * log10(f.toDouble())).toFloat()
    private fun dbToLinear(db: Float): Float = 10.0f.pow(db / 20f)

    private fun saveAll(enabled: Boolean, factor: Float) {
        val db  = factorToDb(factor).coerceIn(-6f, 12f)
        val adv = swForceMic.isChecked || !swRespectFmt.isChecked || !swAgc.isChecked || !swNs.isChecked

        Prefs.saveFromUi(this, enabled, db, adv)
        Prefs.saveAdvancedToggles(
            this,
            forceMic   = swForceMic.isChecked,
            agc        = swAgc.isChecked,
            ns         = swNs.isChecked,
            respectFmt = swRespectFmt.isChecked,
            preboost   = enabled
        )

        Prefs.makePrefsWorldReadable(this)

        // Sugerir a los procesos hookeados recargar; no depende del paquete
        sendBroadcast(Intent("com.d4vram.whatsmicfix.RELOAD"))

        updateLabel(enabled, factor)
    }

    private fun updateLabel(enabled: Boolean, factor: Float) {
        val db = 20 * log10(factor.toDouble())
        val dbStr = if (db >= 0) "+%.1f dB".format(db) else "%.1f dB".format(db)
        tvFactor.text = if (enabled) "Ganancia: x%.2f ($dbStr)".format(factor) else "Ganancia desactivada"
        val color = when {
            !enabled -> android.graphics.Color.GRAY
            factor > 3.0f -> 0xFFFF5722.toInt()
            factor > 2.0f -> 0xFFFF9800.toInt()
            else -> android.graphics.Color.WHITE
        }
        tvFactor.setTextColor(color)
    }

    private fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "No se pudo abrir el enlace.", Toast.LENGTH_SHORT).show()
        }
    }

}
