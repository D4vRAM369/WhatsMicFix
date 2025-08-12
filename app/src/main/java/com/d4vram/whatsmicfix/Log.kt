package com.d4vram.whatsmicfix

import de.robv.android.xposed.XposedBridge

object Logx {
    private const val TAG = "WhatsMicFix"
    var enabled = true

    fun d(msg: String) {
        if (enabled) XposedBridge.log("D/$TAG: $msg")
    }

    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("E/$TAG: $msg")
        t?.let { XposedBridge.log(it) }
    }
}
