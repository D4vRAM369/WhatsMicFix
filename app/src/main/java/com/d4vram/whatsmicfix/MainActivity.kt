package com.d4vram.whatsmicfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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

    // Guardamos último estado para TTL
    private var lastActive = false
    private var lastReason = "init"
    private var lastTs = 0L

    // Recibe estado del hook (WFM_STATUS) y el “canario” (DIAG_EVENT)
    private val statusRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.d4vram.whatsmicfix.WFM_STATUS" -> {
                    lastActive = intent.getBooleanExtra("active", false)
                    lastReason = intent.getStringExtra("reason") ?: ""
                    lastTs     = intent.getLongExtra("ts", 0L)
                    applyStatusWithTtl()
                }
                "com.d4vram.whatsmicfix.DIAG_EVENT" -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    if (msg.startsWith("Hook activo en")) {
                        // canario: hook cargado en el proceso de WA/W4B
                        statusText.text = "Hook cargado en WhatsApp"
                    }
                }
            }
        }
    }

    // Envía broadcast explícito a WA/WA Business
    private fun sendToWa(action: String) {
        val pkgs = arrayOf("com.whatsapp", "com.whatsapp.w4b")
        for (p in pkgs) {
            try { sendBroadcast(Intent(action).setPackage(p)) } catch (_: Throwable) {}
        }
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

        btnCheck.setOnClickListener { sendToWa("com.d4vram.whatsmicfix.PING") }

        // Seek
        seekBoost.max = 250
        if (Build.VERSION.SDK_INT >= 26) seekBoost.min = 50

        // Estado inicial desde Prefs
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

        // RELOAD+PING a WA/W4B
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

    /** Mantiene verde hasta 4s tras el último pre-boost para que lo percibas aunque sueltes la nota. */
    private fun applyStatusWithTtl() {
        val ageMs = System.currentTimeMillis() - lastTs
        val effectiveActive =
            if (lastActive) true
            else (lastReason == "preboost" && ageMs in 0..4000)

        val color = if (effectiveActive) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
        val bg = statusDot.background
        if (bg is GradientDrawable) bg.setColor(color) else bg.setTint(color)

        val freshness = if (ageMs < 3000) "" else " (desfasado)"
        statusText.text = when {
            lastReason == "preboost" && !lastActive && effectiveActive ->
                "Pre-boost reciente (≤4s)$freshness"
            lastActive ->
                "Pre-boost activo$freshness"
            else ->
                "Sin pre-boost$freshness${if (lastReason.isNotEmpty()) " · $lastReason" else ""}"
        }
    }
}
