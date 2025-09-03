package com.d4vram.whatsmicfix

import android.util.Log

object Logx {
    private const val TAG = "WhatsMicFix"
    var enabled = true

    private fun isXposedAvailable(): Boolean {
        return try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    fun d(msg: String) {
        if (!enabled) return
        
        if (isXposedAvailable()) {
            try {
                val xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge")
                val logMethod = xposedBridge.getMethod("log", String::class.java)
                logMethod.invoke(null, "D/$TAG: $msg")
            } catch (e: Exception) {
                Log.d(TAG, msg)
            }
        } else {
            Log.d(TAG, msg)
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        if (isXposedAvailable()) {
            try {
                val xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge")
                val logMethod = xposedBridge.getMethod("log", String::class.java)
                val logThrowableMethod = xposedBridge.getMethod("log", Throwable::class.java)
                logMethod.invoke(null, "E/$TAG: $msg")
                t?.let { logThrowableMethod.invoke(null, it) }
            } catch (e: Exception) {
                Log.e(TAG, msg, t)
            }
        } else {
            Log.e(TAG, msg, t)
        }
    }
    
    fun w(msg: String) {
        if (!enabled) return
        
        if (isXposedAvailable()) {
            try {
                val xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge")
                val logMethod = xposedBridge.getMethod("log", String::class.java)
                logMethod.invoke(null, "W/$TAG: $msg")
            } catch (e: Exception) {
                Log.w(TAG, msg)
            }
        } else {
            Log.w(TAG, msg)
        }
    }
}
