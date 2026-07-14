package com.simemu.spoof.utils

import com.simemu.spoof.core.Constants
import de.robv.android.xposed.XposedBridge

/**
 * Thin logging wrapper. XposedBridge.log surfaces in the LSPosed log viewer,
 * which is where you debug hook behaviour from inside a target process.
 */
object XLog {
    private const val VERBOSE = true

    fun d(msg: String) {
        if (VERBOSE) XposedBridge.log("${Constants.LOG_TAG}: $msg")
    }

    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("${Constants.LOG_TAG}: [ERR] $msg")
        if (t != null) XposedBridge.log(t)
    }
}
