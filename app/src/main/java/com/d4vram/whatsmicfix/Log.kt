package com.d4vram.whatsmicfix

import android.util.Log

object Logx {
    private const val TAG = "WhatsMicFix"

    // Detecta Xposed en tiempo de ejecución SIN referenciarlo directamente
    private val xposedClass by lazy {
        try { Class.forName("de.robv.android.xposed.XposedBridge") } catch (_: Throwable) { null }
    }

    private fun xlog(line: String) {
        try {
            val m = xposedClass?.getMethod("log", String::class.java)
            m?.invoke(null, line)
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun xlog(t: Throwable) {
        try {
            val m = xposedClass?.getMethod("log", Throwable::class.java)
            m?.invoke(null, t)
        } catch (_: Throwable) { /* ignore */ }
    }

    fun d(msg: String) {
        if (xposedClass != null) xlog("D/$TAG: $msg") else Log.d(TAG, msg)
    }

    fun w(msg: String) {
        if (xposedClass != null) xlog("W/$TAG: $msg") else Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (xposedClass != null) {
            xlog("E/$TAG: $msg")
            if (t != null) xlog(t)
        } else {
            if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        }
    }
}
