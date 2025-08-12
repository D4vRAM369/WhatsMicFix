package com.d4vram.whatsmicfix

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    private val targetPkgs = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b" // Business
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!targetPkgs.contains(lpparam.packageName)) return
        Logx.d("Cargando hooks en ${lpparam.packageName}")
        AudioHooks.install(lpparam)
    }
}
