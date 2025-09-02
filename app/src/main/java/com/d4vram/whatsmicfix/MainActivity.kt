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

    private var recentPreboostAgeMs: Long = Long.MAX_VALUE
    private var recentPreboostBoost: Float = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var checkStartTime = 0L
    private var isCheckingFunctionality = false
    private var checkTimeout: Runnable? = null

    // Para detectar distorsión
    private var lastBoostValue = 1.0f
    private var distortionWarningShown = false

    private val statusRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.d4vram.whatsmicfix.WFM_STATUS" -> {
                    lastActive = intent.getBooleanExtra("active", false)
                    lastReason = intent.getStringExtra("reason") ?: ""
                    lastTs     = intent.getLongExtra("ts", 0L)

                    // NUEVO: leer el "preboost reciente"
                    recentPreboostAgeMs = intent.getLongExtra("recentPreboostAgeMs", Long.MAX_VALUE)
                    recentPreboostBoost = intent.getFloatExtra("recentPreboostBoost", 0f)

                    val boostFactor = intent.getFloatExtra("boostFactor", 0f)
                    val totalBoosts = intent.getIntExtra("totalBoosts", 0)

                    // ACTUALIZADO: éxito si hubo preboost en vivo o en ≤ 8 s

                    if (isCheckingFunctionality && lastReason == "preboost" || recentPreboostAgeMs <= 8000)) {
                        val bf = if  (recentPreboostBoost > 0f) recentPreboostBoost else intent.getFloatExtra("boostFactor", 0f)
                        showCheckResult(true, "Audio detectado y procesado correctamente (${String.format("%.1f", boostFactor)}x)")
                    }

                    if (boostFactor > 0f && totalBoosts > 0) {
                        statusText.text = "Boost activo: ${String.format("%.1f", boostFactor)}x (${totalBoosts} procesados)"
                    }
                    
                    applyStatusWithTtl()
                }
                "com.d4vram.whatsmicfix.DIAG_EVENT" -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    if (msg.startsWith("Hook activo en")) {
                        statusText.text = "Hook cargado en WhatsApp ✓"
                    }
                }
            }
        }
    }

    private fun sendToWa(action: String) {
        val pkgs = arrayOf("com.whatsapp", "com.whatsapp.w4b")
        for (p in pkgs) {
            try {
                sendBroadcast(Intent(action).setPackage(p))
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        Prefs.reloadIfStale(this)
        
        // Valores iniciales con 8dB como pediste
        if (Prefs.boostDb <= 0f || Prefs.boostDb > 12f) {
            Prefs.saveFromUi(this, true, 8.0f, false) // +8dB por defecto
            Prefs.saveAdvancedToggles(this, preboost = true)
            makePrefsWorldReadable()
            Prefs.reloadIfStale(this)
        }

        initializeViews()
        setupListeners()
        loadCurrentSettings()
        makePrefsWorldReadable()

        // Verificar estado inicial
        sendToWa("com.d4vram.whatsmicfix.PING")
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
        btnCheck.setOnClickListener { startFunctionalityCheck() }

        // Rango ampliado: 0.5x..4.0x => 50..400 para soportar hasta 12dB
        seekBoost.max = 400
        if (Build.VERSION.SDK_INT >= 26) seekBoost.min = 50

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            saveAll(isChecked, progressToFactor(seekBoost.progress))
            if (!isChecked) {
                statusText.text = "Módulo desactivado"
            }
        }

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val factor = progressToFactor(p)

                if (fromUser) {
                    // Advertencia si el boost es muy alto
                    if (factor > 3.0f && !distortionWarningShown) {
                        Toast.makeText(
                            this@MainActivity,
                            "⚠️ Boost muy alto - El compresor dinámico evitará distorsión",
                            Toast.LENGTH_SHORT
                        ).show()
                        distortionWarningShown = true
                    }
                    saveAll(swEnable.isChecked, factor)
                } else {
                    updateLabel(swEnable.isChecked, factor)
                }

                lastBoostValue = factor
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                distortionWarningShown = false
            }
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
        sendToWa("com.d4vram.whatsmicfix.PING")
    }
<<<<<<< HEAD
=======
    sendToWa("com.d4vram.whatsmicfix.PING")
    
    // Mini-heartbeat para desatascar el estado
    for (i in 1..9) {
        handler.postDelayed({ sendToWa("com.d4vram.whatsmicfix.PING") }, 1000L * i)
    }
}
>>>>>>> temp-commits

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusRx) } catch (_: Throwable) {}

        if (isCheckingFunctionality) {
            isCheckingFunctionality = false
            checkTimeout?.let { handler.removeCallbacks(it) }
            checkTimeout = null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        checkTimeout?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
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

        makePrefsWorldReadable()

        // Enviar notificación de cambios
        sendToWa("com.d4vram.whatsmicfix.RELOAD")

        // Pequeño delay antes de pedir estado actualizado
        handler.postDelayed({
            sendToWa("com.d4vram.whatsmicfix.PING")
        }, 100)

        updateLabel(enabled, factor)
    }

    private fun makePrefsWorldReadable() {
        try {
            // Múltiples intentos con diferentes rutas
            val paths = listOf(
                File(applicationInfo.dataDir + "/shared_prefs/${Prefs.FILE}.xml"),
                File("/data/data/$packageName/shared_prefs/${Prefs.FILE}.xml")
            )

            paths.forEach { file ->
                if (file.exists()) {
                    file.setReadable(true, false)
                    // También el directorio
                    file.parentFile?.apply {
                        setReadable(true, false)
                        setExecutable(true, false)
                    }
                }
            }

            Prefs.makePrefsWorldReadable(this)
        } catch (t: Throwable) {
            // Log silencioso, no mostrar al usuario
        }
    }

    private fun updateLabel(enabled: Boolean, factor: Float) {
        val db = 20 * log10(factor.toDouble())
        val dbStr = if (db >= 0) "+%.1f dB".format(db) else "%.1f dB".format(db)

        tvFactor.text = if (enabled) {
            "Ganancia: x%.2f ($dbStr)".format(factor)
        } else {
            "Ganancia desactivada"
        }

        // Cambiar color según el nivel
        val color = when {
            !enabled -> android.graphics.Color.GRAY
            factor > 3.0f -> 0xFFFF5722.toInt() // Naranja fuerte para boost muy alto
            factor > 2.0f -> 0xFFFF9800.toInt() // Naranja para boost alto
            else -> android.graphics.Color.WHITE
        }
        tvFactor.setTextColor(color)
    }

    private fun applyStatusWithTtl() {
        val ageMs = System.currentTimeMillis() - lastTs
        val effectiveActive = if (lastActive) true else (recentPreboostAgeMs <= 8000)

        val color = when {
            effectiveActive -> 0xFF2ECC71.toInt() // Verde
            ageMs > 10000 -> 0xFFBDC3C7.toInt()   // Gris (sin datos recientes)
            else -> 0xFFE74C3C.toInt()            // Rojo
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
            lastReason == "preboost" && effectiveActive ->
                "Pre-boost activo$freshness"
            lastActive ->
                "Procesando audio$freshness"
            lastReason == "release" ->
                "En espera$freshness"
            else ->
                "Sin actividad$freshness"
        }
    }

    private fun startFunctionalityCheck() {
        isCheckingFunctionality = true
        checkStartTime = System.currentTimeMillis()

        btnCheck.text = "ENVÍA UN AUDIO EN WHATSAPP AHORA"
        if (Build.VERSION.SDK_INT >= 21) {
            btnCheck.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
        }
        btnCheck.isEnabled = false
        
        statusText.text = "Esperando audio de WhatsApp..."
        val bg = statusDot.background
        if (bg is GradientDrawable) bg.setColor(0xFFFF9800.toInt()) else bg.setTint(0xFFFF9800.toInt())

        sendToWa("com.d4vram.whatsmicfix.PING")

        checkTimeout = Runnable {
            if (isCheckingFunctionality) {
                showCheckResult(false, "No se detectó actividad de audio")
            }
        }
        handler.postDelayed(checkTimeout!!, 15000) // 15 segundos
    }
    
    private fun showCheckResult(success: Boolean, message: String) {
        isCheckingFunctionality = false
        checkTimeout?.let { handler.removeCallbacks(it) }
        checkTimeout = null
        
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