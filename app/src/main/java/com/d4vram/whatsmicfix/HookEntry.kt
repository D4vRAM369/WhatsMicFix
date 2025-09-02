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

    private val targetPkgs = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!targetPkgs.contains(lpparam.packageName)) return
        Logx.d("Cargando hooks en ${lpparam.packageName}")

        // Hook temprano para capturar Context y registrar receptores
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return
                    AppCtx.set(ctx)
                    Logx.d("Application.attach capturado; ctx establecido")

                    // 🔔 Canario: avisa a tu app que el hook está vivo en este proceso
                    try {
                        ctx.sendBroadcast(
                            Intent("com.d4vram.whatsmicfix.DIAG_EVENT")
                                .setPackage(APP_PKG)
                                .putExtra("msg", "Hook activo en ${ctx.packageName}")
                        )
                    } catch (_: Throwable) {}

                    // RELOAD
                    try {
                        val filter = IntentFilter("com.d4vram.whatsmicfix.RELOAD")
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            ctx.registerReceiver(object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, i: Intent?) {
                                    if (i?.action == "com.d4vram.whatsmicfix.RELOAD") {
                                        Prefs.forceReload()
                                        Logx.d("RELOAD recibido; invalidado TTL de prefs")
                                    }
                                }
                            }, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            @Suppress("UnspecifiedRegisterReceiverFlag")
                            ctx.registerReceiver(object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, i: Intent?) {
                                    if (i?.action == "com.d4vram.whatsmicfix.RELOAD") {
                                        Prefs.forceReload()
                                        Logx.d("RELOAD recibido; invalidado TTL de prefs")
                                    }
                                }
                            }, filter)
                        }
                        Logx.d("Receiver RELOAD registrado")
                    } catch (t: Throwable) {
                        Logx.e("Error registrando receiver RELOAD", t)
                    }

                    // PING
                    try {
                        val filter = IntentFilter("com.d4vram.whatsmicfix.PING")
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            ctx.registerReceiver(object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, i: Intent?) {
                                    if (i?.action == "com.d4vram.whatsmicfix.PING") {
                                        try { AudioHooks.respondPing() } catch (_: Throwable) {}
                                    }
                                }
                            }, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            @Suppress("UnspecifiedRegisterReceiverFlag")
                            ctx.registerReceiver(object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, i: Intent?) {
                                    if (i?.action == "com.d4vram.whatsmicfix.PING") {
                                        try { AudioHooks.respondPing() } catch (_: Throwable) {}
                                    }
                                }
                            }, filter)
                        }
                        Logx.d("Receiver PING registrado")
                    } catch (t: Throwable) {
                        Logx.e("Error registrando receiver PING", t)
                    }
                }
            }
        )

        AudioHooks.install(lpparam)
    }
}
