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

    // Controles existentes
    private lateinit var swEnable: Switch
    private lateinit var tvFactor: TextView
    private lateinit var seekBoost: SeekBar
    private lateinit var swForceMic: Switch
    private lateinit var swAgc: Switch
    private lateinit var swNs: Switch
    private lateinit var swRespectFmt: Switch

    // Indicador de estado
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnCheck: Button

    // Receiver que escucha el estado desde el hook (WFM_STATUS)
    private val statusRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.d4vram.whatsmicfix.WFM_STATUS") return
            val active = intent.getBooleanExtra("active", false)
            val reason = intent.getStringExtra("reason") ?: ""
            val ts = intent.getLongExtra("ts", 0L)
            val ageMs = System.currentTimeMillis() - ts
            updateStatusUi(active, reason, ageMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Cargar estado desde preferencias al arrancar (v1.2)
        Prefs.reloadIfStale(this)

        // FindViews
        swEnable = findViewById(R.id.swEnable)
        tvFactor = findViewById(R.id.tvFactor)
        seekBoost = findViewById(R.id.seekBoost)
        swForceMic = findViewById(R.id.swForceMic)
        swAgc = findViewById(R.id.swAgc)
        swNs = findViewById(R.id.swNs)
        swRespectFmt = findViewById(R.id.swRespectFmt)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        btnCheck = findViewById(R.id.btnCheck)

        // Botón "Comprobar" => pide estado actual al hook
        btnCheck.setOnClickListener {
            sendBroadcast(Intent("com.d4vram.whatsmicfix.PING"))
        }

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

        // Pide estado inicial al abrir la pantalla
        sendBroadcast(Intent("com.d4vram.whatsmicfix.PING"))
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.d4vram.whatsmicfix.WFM_STATUS")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusRx, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusRx, filter)
        }
        // refresco por si entramos en foreground
        sendBroadcast(Intent("com.d4vram.whatsmicfix.PING"))
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusRx)
        } catch (_: Throwable) {
            // Por si la actividad se pausa sin haberse registrado aún (carreras)
        }
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
        Prefs.saveFromUi(this, enabled, db, adv)
        // v1.2 — guarda los toggles avanzados por separado
        Prefs.saveAdvancedToggles(
            this,
            forceMic = swForceMic.isChecked,
            agc = swAgc.isChecked,
            ns = swNs.isChecked,
            respectFmt = swRespectFmt.isChecked
        )

        makePrefsWorldReadable()

        // 🔥 Hot-reload: avisa al proceso de WhatsApp para recargar prefs sin matar la app
        sendBroadcast(Intent("com.d4vram.whatsmicfix.RELOAD"))
        // y pide estado para refrescar el indicador
        sendBroadcast(Intent("com.d4vram.whatsmicfix.PING"))

        updateLabel(enabled, factor)
    }

    private fun makePrefsWorldReadable() {
        try {
            File(applicationInfo.dataDir + "/shared_prefs/${Prefs.FILE}.xml")
                .setReadable(true, false)
            Prefs.makePrefsWorldReadable(this)
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

    private fun updateStatusUi(active: Boolean, reason: String, ageMs: Long) {
        val color = if (active) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt() // verde/rojo
        val bg = statusDot.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            bg.setTint(color)
        }
        val freshness = if (ageMs < 3000) "" else " (desfasado)"
        statusText.text = if (active)
            "Pre-boost activo$freshness"
        else
            "Sin pre-boost$freshness${if (reason.isNotEmpty()) " · $reason" else ""}"
    }
}
