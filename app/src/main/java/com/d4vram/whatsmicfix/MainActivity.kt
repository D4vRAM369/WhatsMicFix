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

    // Último estado que envía el hook
    private var lastActive = false
    private var lastReason = "init"
    private var lastTs = 0L

    // Sistema de confirmación mejorado
    private val handler = Handler(Looper.getMainLooper())
    private var audioDetectionStartTime = 0L
    private var hasRecentAudio = false
    private var confirmationTimeout: Runnable? = null
    private var isCheckingFunctionality = false

    // Recibe WFM_STATUS/DIAG_EVENT desde el hook
    private val statusRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.d4vram.whatsmicfix.WFM_STATUS" -> {
                    lastActive = intent.getBooleanExtra("active", false)
                    lastReason = intent.getStringExtra("reason") ?: ""
                    lastTs     = intent.getLongExtra("ts", 0L)
                    
                    // Detectar actividad de audio para confirmación
                    if (lastReason == "preboost" && isCheckingFunctionality) {
                        detectAudioActivity()
                    }
                    
                    applyStatusWithTtl()
                }
                "com.d4vram.whatsmicfix.DIAG_EVENT" -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    if (msg.startsWith("Hook activo en")) {
                        statusText.text = "Hook cargado en WhatsApp"
                    }
                }
            }
        }
    }

    private fun sendToWa(action: String) {
        val pkgs = arrayOf("com.whatsapp", "com.whatsapp.w4b")
        for (p in pkgs) try { sendBroadcast(Intent(action).setPackage(p)) } catch (_: Throwable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        Prefs.reloadIfStale(this)

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

        btnCheck.setOnClickListener { startFunctionalityCheck() }

        // Seek: 0.5x..2.5x  => 50..250
        seekBoost.max = 250
        if (Build.VERSION.SDK_INT >= 26) seekBoost.min = 50

        val enabled = Prefs.enablePreboost
        val factor  = dbToLinear(Prefs.boostDb).coerceIn(0.5f, 2.5f)

        swEnable.isChecked     = enabled
        seekBoost.progress     = (factor * 100).toInt().coerceIn(50, 250)
        swForceMic.isChecked   = Prefs.forceSourceMic
        swAgc.isChecked        = Prefs.enableAgc
        swNs.isChecked         = Prefs.enableNs
        swRespectFmt.isChecked = Prefs.respectAppFormat

        updateLabel(enabled, factor)

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            saveAll(isChecked, progressToFactor(seekBoost.progress))
        }

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) saveAll(swEnable.isChecked, progressToFactor(p))
                else          updateLabel(swEnable.isChecked, progressToFactor(p))
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

        // Pide estado + canario
        sendToWa("com.d4vram.whatsmicfix.PING")
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
        sendToWa("com.d4vram.whatsmicfix.PING")
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusRx) } catch (_: Throwable) {}
    }

    private fun progressToFactor(p: Int) = p.coerceIn(50, 250) / 100f
    private fun factorToDb(f: Float): Float = (20f * log10(f.toDouble())).toFloat()
    private fun dbToLinear(db: Float): Float = 10.0f.pow(db / 20f)

    private fun saveAll(enabled: Boolean, factor: Float) {
        val db  = factorToDb(factor)
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

        makePrefsWorldReadable()
        sendToWa("com.d4vram.whatsmicfix.RELOAD")
        sendToWa("com.d4vram.whatsmicfix.PING")
        updateLabel(enabled, factor)
    }

    private fun makePrefsWorldReadable() {
        try {
            File(applicationInfo.dataDir + "/shared_prefs/${Prefs.FILE}.xml").setReadable(true, false)
            Prefs.makePrefsWorldReadable(this)
        } catch (_: Throwable) {}
    }

    private fun updateLabel(enabled: Boolean, factor: Float) {
        val db = 20 * log10(factor.toDouble())
        val dbStr = if (db >= 0) "+%.1f dB".format(db) else "%.1f dB".format(db)
        tvFactor.text = if (enabled) "Ganancia: x%.2f ($dbStr)".format(factor) else "Ganancia desactivada"
    }

    /** TTL extendido a 8s para mayor estabilidad */
    private fun applyStatusWithTtl() {
        val ageMs = System.currentTimeMillis() - lastTs
        val effectiveActive = if (lastActive) true else (lastReason == "preboost" && ageMs in 0..8000)

        val color = if (effectiveActive) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
        val bg = statusDot.background
        if (bg is GradientDrawable) bg.setColor(color) else bg.setTint(color)

        val freshness = if (ageMs < 5000) "" else " (desfasado)"
        statusText.text = when {
            lastReason == "preboost" && !lastActive && effectiveActive ->
                "Pre-boost reciente (≤8s)$freshness"
            lastActive ->
                "Pre-boost activo$freshness"
            else ->
                "Sin pre-boost$freshness${if (lastReason.isNotEmpty()) " · $lastReason" else ""}"
        }
    }

    private fun startFunctionalityCheck() {
        isCheckingFunctionality = true
        hasRecentAudio = false
        audioDetectionStartTime = System.currentTimeMillis()
        
        // Cambiar estilo del botón durante la comprobación
        btnCheck.text = "ENVÍA UN AUDIO EN WHATSAPP"
        if (Build.VERSION.SDK_INT >= 21) {
            btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt()) // Naranja
        }
        btnCheck.isEnabled = false
        
        statusText.text = "Esperando audio de WhatsApp..."
        val bg = statusDot.background
        if (bg is GradientDrawable) bg.setColor(0xFFFF9800.toInt()) else bg.setTint(0xFFFF9800.toInt()) // Naranja
        
        // Enviar ping inicial
        sendToWa("com.d4vram.whatsmicfix.PING")
        
        // Timeout de 30 segundos para la comprobación
        confirmationTimeout = Runnable {
            if (isCheckingFunctionality) {
                showCheckResult(false, "Tiempo agotado sin detectar audio")
            }
        }
        handler.postDelayed(confirmationTimeout!!, 30000)
    }
    
    private fun detectAudioActivity() {
        if (!isCheckingFunctionality) return
        
        if (!hasRecentAudio) {
            hasRecentAudio = true
            statusText.text = "Audio detectado! Esperando confirmación..."
            
            // Esperar 5-10 segundos después de la última actividad
            handler.removeCallbacks(confirmationTimeout ?: return)
            confirmationTimeout = Runnable {
                if (isCheckingFunctionality && hasRecentAudio) {
                    val timeSinceLastAudio = System.currentTimeMillis() - lastTs
                    if (timeSinceLastAudio >= 5000) { // 5 segundos de espera
                        checkFinalResult()
                    } else {
                        // Esperar un poco más
                        handler.postDelayed(confirmationTimeout!!, 2000)
                    }
                }
            }
            handler.postDelayed(confirmationTimeout!!, 7000) // 7 segundos de gracia
        }
    }
    
    private fun checkFinalResult() {
        val recentActivity = System.currentTimeMillis() - lastTs < 8000
        val hadPreboost = hasRecentAudio && (lastReason == "preboost" || recentActivity)
        
        showCheckResult(hadPreboost, if (hadPreboost) "Funcionamiento confirmado" else "No se detectó pre-boost")
    }
    
    private fun showCheckResult(success: Boolean, message: String) {
        isCheckingFunctionality = false
        handler.removeCallbacks(confirmationTimeout ?: return)
        
        if (success) {
            btnCheck.text = "✓ FUNCIONANDO CORRECTAMENTE"
            if (Build.VERSION.SDK_INT >= 21) {
                btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt()) // Verde
            }
            statusText.text = "✓ $message - Sistema estable"
            val bg = statusDot.background
            if (bg is GradientDrawable) bg.setColor(0xFF4CAF50.toInt()) else bg.setTint(0xFF4CAF50.toInt())
        } else {
            btnCheck.text = "⚠ REVISAR CONFIGURACIÓN"
            if (Build.VERSION.SDK_INT >= 21) {
                btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE74C3C.toInt()) // Rojo
            }
            statusText.text = "⚠ $message"
            val bg = statusDot.background
            if (bg is GradientDrawable) bg.setColor(0xFFE74C3C.toInt()) else bg.setTint(0xFFE74C3C.toInt())
        }
        
        // Restablecer botón después de 5 segundos
        handler.postDelayed({
            btnCheck.text = "COMPROBAR FUNCIONAMIENTO"
            if (Build.VERSION.SDK_INT >= 21) {
                btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2196F3.toInt()) // Azul
            }
            btnCheck.isEnabled = true
            applyStatusWithTtl() // Volver al estado normal
        }, 5000)
    }
}
