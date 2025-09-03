package com.d4vram.whatsmicfix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicReference

private const val APP_PKG = "com.d4vram.whatsmicfix" // para broadcasts de vuelta a tu app

/** Guarda un ApplicationContext accesible desde los hooks. */
object AppCtx {
    private val ref = AtomicReference<Context?>(null)
    fun set(ctx: Context) { ref.compareAndSet(null, ctx.applicationContext) }
    fun get(): Context? = ref.get()
}

class HookEntry : IXposedHookLoadPackage {

    // Target UNIVERSAL - todos los procesos que usan audio + sistema
    private val targetPkgs = setOf(
        "com.whatsapp", "com.whatsapp.w4b",           // WhatsApp
        "android",                                    // Sistema Android
        "system_server",                              // Servidor del sistema
        "com.android.server.telecom",                 // Telefonía
        "media.audio",                                // Servicios de audio
        "audioserver"                                 // Servidor de audio
    )

    // Apps de audio populares para máxima cobertura
    private val audioApps = setOf(
        "com.google.android.apps.recorder",           // Grabadora Google
        "com.samsung.android.app.memo",
        "com.sec.android.app.voicenote",
        "com.android.soundrecorder",                  // AOSP
        "org.telegram.messenger",
        "com.facebook.orca",                          // Messenger
        "us.zoom.videomeetings",
        "com.skype.raider",
        "com.discord"
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName

        // Hook UNIVERSAL: apps de audio o sistema
        if (!targetPkgs.contains(pkg) && !audioApps.contains(pkg) && !isAudioRelated(pkg)) return
        Logx.d("Cargando hooks UNIVERSALES en $pkg")

        installUniversalHooks(lpparam)
    }

    private fun isAudioRelated(pkg: String): Boolean {
        val audioKeywords = listOf("audio", "record", "voice", "sound", "call", "phone", "media")
        return audioKeywords.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun installUniversalHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val ctx = param.args[0] as? Context ?: return
                        AppCtx.set(ctx)
                        Logx.d("Application.attach capturado; ctx=${ctx.packageName}")

                        // Usar el contexto que tengamos disponible
                        val workingCtx = ctx.applicationContext ?: ctx

                        // Canario -> tu app
                        try {
                            workingCtx.sendBroadcast(
                                Intent("com.d4vram.whatsmicfix.DIAG_EVENT")
                                    .setPackage(APP_PKG)
                                    .putExtra("msg", "Hook activo en ${ctx.packageName}")
                                    .putExtra("hookVersion", "1.3-stable")
                                    .putExtra("timestamp", System.currentTimeMillis())
                            )
                        } catch (t: Throwable) {
                            Logx.e("Error enviando canario", t)
                        }

                        // RELOAD
                        try {
                            val filter = IntentFilter("com.d4vram.whatsmicfix.RELOAD")
                            val reloadReceiver = object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, i: Intent?) {
                                    try {
                                        if (i?.action == "com.d4vram.whatsmicfix.RELOAD") {
                                            Prefs.forceReload()
                                            Logx.d("RELOAD recibido; invalidado TTL de prefs")
                                            c?.sendBroadcast(
                                                Intent("com.d4vram.whatsmicfix.DIAG_EVENT")
                                                    .setPackage(APP_PKG)
                                                    .putExtra("msg", "RELOAD procesado correctamente")
                                            )
                                        }
                                    } catch (t: Throwable) {
                                        Logx.e("Error procesando RELOAD", t)
                                    }
                                }
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                workingCtx.registerReceiver(reloadReceiver, filter, Context.RECEIVER_EXPORTED)
                            } else {
                                @Suppress("UnspecifiedRegisterReceiverFlag")
                                workingCtx.registerReceiver(reloadReceiver, filter)
                            }
                        } catch (t: Throwable) {
                            Logx.e("Error registrando receiver RELOAD", t)
                        }

                        // PING
                        try {
                            val filter = IntentFilter("com.d4vram.whatsmicfix.PING")
                            val pingReceiver = object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, i: Intent?) {
                                    try {
                                        if (i?.action == "com.d4vram.whatsmicfix.PING") {
                                            Logx.d("PING recibido, enviando estado")
                                            AudioHooks.respondPing()
                                            c?.sendBroadcast(
                                                Intent("com.d4vram.whatsmicfix.DIAG_EVENT")
                                                    .setPackage(APP_PKG)
                                                    .putExtra("msg", "PING procesado - hook respondió")
                                            )
                                        }
                                    } catch (t: Throwable) {
                                        Logx.e("Error procesando PING", t)
                                    }
                                }
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                workingCtx.registerReceiver(pingReceiver, filter, Context.RECEIVER_EXPORTED)
                            } else {
                                @Suppress("UnspecifiedRegisterReceiverFlag")
                                workingCtx.registerReceiver(pingReceiver, filter)
                            }
                        } catch (t: Throwable) {
                            Logx.e("Error registrando receiver PING", t)
                        }

                    } catch (mainError: Throwable) {
                        Logx.e("Error crítico en Application.attach hook", mainError)
                    }
                }
            }
        )

        AudioHooks.install(lpparam)
    }
}
