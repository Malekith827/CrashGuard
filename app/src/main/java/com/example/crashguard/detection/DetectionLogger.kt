package com.example.crashguard.detection

import android.util.Log

object DetectionLogger {
    fun d(tag: String, msg: String) {
        try { Log.d(tag, msg) } catch (e: Throwable) { println("[$tag] $msg") }
    }
    fun i(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (e: Throwable) { println("[$tag] $msg") }
    }
    fun w(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (e: Throwable) { println("WARN [$tag] $msg") }
    }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        try { Log.e(tag, msg, t) } catch (e: Throwable) { println("ERROR [$tag] $msg: ${t?.message}") }
    }
}
