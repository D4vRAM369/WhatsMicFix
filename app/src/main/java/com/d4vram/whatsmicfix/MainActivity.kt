package com.d4vram.whatsmicfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnCheck: Button

    private var lastActive = false
    private var lastReason = "init"
    private var lastTs = 0L

    private val handler = Handler(Looper.getMainLooper())

    // Comprobación universal
    private var isChecking = false
    private var hadAudio = false
    private var inactivityTimer: Runnable? = null // 8s desde el último audio
    private var masterTimer: Runnable? = null     // 20s para no quedar bloqueado
    private var lastBoostSeen = 0f

    private var distortionWarningShown = false

    private val statusRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                "com.d4vram.whatsmicfix.WFM_STATUS" -> {
                    lastActive = intent.getBooleanExtra("active", false)
                    lastReason = intent.getStringExtra("reason") ?: ""
                    lastTs     = intent.getLongExtra("ts", 0L)

                    val boostFactor = intent.getFloatExtra("boostFactor", 0f)
                    val totalBoosts = intent.getIntExtra("totalBoosts", 0)
                    if (boostFactor > 0f) lastBoostSeen = boostFactor

                    // ÉXITO UNIVERSAL: cualquier app que dispare "preboost"
                    if (isChecking && lastReason == "preboost") {
                        hadAudio = true
                        scheduleInactivityWindow()
                        statusText.text = "Boost activo: x${"%.1f".format(lastBoostSeen)} (${totalBoosts} procesados)"
                    }

                    applyStatusWithTtl()
                }
                "com.d4vram.whatsmicfix.DIAG_EVENT" -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    if (msg.startsWith("Hook activo en")) {
                        // meramente informativo
                    }
                }
            }
        }
    }

    private fun ensureStatusDotBg() {
        val bg = statusDot.background
        if (bg !is android.graphics.drawable.GradientDrawable) {
            val d = android.graphics.drawable.GradientDrawable()
            d.shape = android.graphics.drawable.GradientDrawable.OVAL
            d.setColor(0xFFBDBDBD.toInt()) // gris neutro
            statusDot.background = d
        }
    }

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
        ensureStatusDotBg()
        loadCurrentSettings()
        setupListeners()
        Prefs.makePrefsWorldReadable(this)

        // PING opcional (ya no es necesario para el check universal, pero mantiene diagnóstico)
        sendBroadcast(Intent("com.d4vram.whatsmicfix.PING"))
    }

    private fun initializeViews() {
        swEnable     = findViewById(R.id.swEnable)
        tvFactor     = findViewById(R.id.tvFactor)
        seekBoost    = findViewById(R.id.seekBoost)
        swForceMic   = findViewById(R.id.swForceMic)
        swAgc        = findViewById(R.id.swAgc)
        swNs         = findViewById(R.id.swNs)
        swRespectFmt = findViewById(R.id.swRespectFmt)
        statusDot    = findViewById(R.id.statusDot)
        statusText   = findViewById(R.id.statusText)
        btnCheck     = findViewById(R.id.btnCheck)
    }

    private fun setupListeners() {
        btnCheck.setOnClickListener { startUniversalCheck() }

        // 0.5x..4.0x => 50..400
        seekBoost.max = 400
        if (Build.VERSION.SDK_INT >= 26) seekBoost.min = 50

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            saveAll(isChecked, progressToFactor(seekBoost.progress))
            if (!isChecked) statusText.text = "Módulo desactivado"
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

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.d4vram.whatsmicfix.WFM_STATUS")
            addAction("com.d4vram.whatsmicfix.DIAG_EVENT")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusRx, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusRx, filter)
        }
        sendBroadcast(Intent("com.d4vram.whatsmicfix.PING"))
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusRx) } catch (_: Throwable) {}
        cancelCheckTimers()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCheckTimers()
        handler.removeCallbacksAndMessages(null)
    }

    private fun cancelCheckTimers() {
        inactivityTimer?.let { handler.removeCallbacks(it) }; inactivityTimer = null
        masterTimer?.let { handler.removeCallbacks(it) };     masterTimer = null
        isChecking = false; hadAudio = false
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
        handler.postDelayed({ sendBroadcast(Intent("com.d4vram.whatsmicfix.PING")) }, 100)

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

    private fun applyStatusWithTtl() {
        val ageMs = System.currentTimeMillis() - lastTs
        val effectiveActive = if (lastActive) true else (lastReason == "preboost" && ageMs in 0..8000)

        val color = when {
            effectiveActive -> 0xFF2ECC71.toInt()
            ageMs > 10000   -> 0xFFBDC3C7.toInt()
            else            -> 0xFFE74C3C.toInt()
        }

        val bg = statusDot.background
        if (bg is GradientDrawable) bg.setColor(color) else bg.setTint(color)

        val freshness = when {
            ageMs < 2000 -> " (en vivo)"
            ageMs < 5000 -> ""
            ageMs < 10000 -> " (hace ${ageMs/1000}s)"
            else -> " (sin señal)"
        }

        statusText.text = when {
            lastReason == "preboost" && effectiveActive -> "Pre-boost activo$freshness"
            lastActive -> "Procesando audio$freshness"
            lastReason == "release" -> "En espera$freshness"
            else -> "Sin actividad$freshness"
        }
    }

    private fun startUniversalCheck() {
        cancelCheckTimers()
        isChecking = true; hadAudio = false; lastBoostSeen = 0f

        btnCheck.text = "ENVÍA UN AUDIO EN CUALQUIER APP"
        if (Build.VERSION.SDK_INT >= 21) {
            btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
        }
        btnCheck.isEnabled = false

        statusText.text = "Esperando audio..."
        val bg = statusDot.background
        if (bg is GradientDrawable) bg.setColor(0xFFFF9800.toInt()) else bg.setTint(0xFFFF9800.toInt())

        // Timer maestro (20s)
        masterTimer = Runnable {
            if (isChecking && !hadAudio) showCheckResult(false, "No se detectó actividad de audio")
        }
        handler.postDelayed(masterTimer!!, 20_000L)
    }

    private fun scheduleInactivityWindow() {
        inactivityTimer?.let { handler.removeCallbacks(it) }
        inactivityTimer = Runnable {
            if (isChecking) {
                val bf = if (lastBoostSeen > 0f) lastBoostSeen else progressToFactor(seekBoost.progress)
                showCheckResult(true, "Audio detectado y procesado (${String.format("%.1f", bf)}x)")
            }
        }
        handler.postDelayed(inactivityTimer!!, 8_000L)
    }

    private fun showCheckResult(success: Boolean, message: String) {
        isChecking = false
        inactivityTimer?.let { handler.removeCallbacks(it) }; inactivityTimer = null
        masterTimer?.let { handler.removeCallbacks(it) };     masterTimer = null

        if (success) {
            btnCheck.text = "✓ FUNCIONANDO"
            if (Build.VERSION.SDK_INT >= 21) {
                btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            }
            statusText.text = "✓ $message"
            val bg = statusDot.background
            if (bg is GradientDrawable) bg.setColor(0xFF4CAF50.toInt()) else bg.setTint(0xFF4CAF50.toInt())
        } else {
            btnCheck.text = "⚠ NO DETECTADO"
            if (Build.VERSION.SDK_INT >= 21) {
                btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE74C3C.toInt())
            }
            statusText.text = "⚠ $message"
            val bg = statusDot.background
            if (bg is GradientDrawable) bg.setColor(0xFFE74C3C.toInt()) else bg.setTint(0xFFE74C3C.toInt())
        }

        handler.postDelayed({
            btnCheck.text = "COMPROBAR FUNCIONAMIENTO"
            if (Build.VERSION.SDK_INT >= 21) {
                btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2196F3.toInt())
            }
            btnCheck.isEnabled = true
            applyStatusWithTtl()
        }, 3000)
    }
}
