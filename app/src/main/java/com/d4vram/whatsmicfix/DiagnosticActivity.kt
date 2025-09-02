package com.d4vram.whatsmicfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private val log = StringBuilder(1024)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.d4vram.whatsmicfix.DIAG_EVENT") return
            val msg = intent.getStringExtra("msg") ?: return
            appendLine(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnClear = Button(this).apply {
            text = "Limpiar"
            setOnClickListener {
                log.clear()
                tv.text = ""
            }
        }

        val btnShare = Button(this).apply {
            text = "Compartir"
            setOnClickListener {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, log.toString())
                }
                startActivity(Intent.createChooser(i, "Compartir diagnóstico"))
            }
        }

        btns.addView(btnClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(btnShare, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(16, 16, 16, 16)
            textSize = 12f
        }

        root.addView(btns)
        root.addView(tv)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.d4vram.whatsmicfix.DIAG_EVENT")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(rx, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(rx) } catch (_: Throwable) {}
    }

    private fun appendLine(m: String) {
        val line = "${fmt.format(Date())}  $m\n"
        log.append(line)
        tv.append(line)
    }
}
