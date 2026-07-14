package com.simemu.spoof.hooks

import android.net.Uri
import com.simemu.spoof.utils.Reflect
import com.simemu.spoof.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections

/**
 * DIAGNOSTIC MODE (config.diagnostics = true).
 *
 * Logs what a scoped app actually asks for, so we can see WHY it doesn't detect a
 * SIM. Two sources are covered:
 *   1. Every getter on TelephonyManager / SubscriptionManager the app calls, with
 *      the value it received (after our spoof hooks run).
 *   2. Direct ContentResolver query()/call() to the telephony database
 *      (content://telephony/...), which bypasses the Java API entirely.
 *
 * Output is deduped by "class.method" so the log shows the SET of things the app
 * relies on, not a flood. Read it with:  adb logcat | grep 'SimEmulator: DIAG'
 */
object DiagnosticHook {

    private val seen = Collections.synchronizedSet(HashSet<String>())

    fun install(tmCls: Class<*>, smCls: Class<*>, cl: ClassLoader) {
        logGetters(tmCls, "TM")
        logGetters(smCls, "SM")
        hookContentResolver()
        XLog.d("DIAG diagnostics active")
    }

    /** Log first call to each get / has / is method with its return value. */
    private fun logGetters(cls: Class<*>, tag: String) {
        cls.declaredMethods
            .filter { it.name.startsWith("get") || it.name.startsWith("has") || it.name.startsWith("is") }
            .distinctBy { it.name }
            .forEach { m ->
                runCatching {
                    m.isAccessible = true
                    de.robv.android.xposed.XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val key = "$tag.${m.name}"
                            if (seen.add(key)) XLog.d("DIAG $key -> ${param.result}")
                        }
                    })
                }
            }
    }

    /** Log telephony-related ContentResolver access (the API-bypass path). */
    private fun hookContentResolver() {
        val cr = Reflect.classOrNull("android.content.ContentResolver", null) ?: return
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uri = param.args.firstOrNull { it is Uri } as? Uri ?: return
                val s = uri.toString()
                if (s.contains("telephony") || s.contains("sim", true) ||
                    s.contains("carrier", true) || s.contains("subscription", true)) {
                    val key = "CR ${param.method.name} $s"
                    if (seen.add(key)) XLog.d("DIAG ContentResolver.${param.method.name}: $uri")
                }
            }
        }
        Reflect.hookAllByName(cr, "query", cb)
        Reflect.hookAllByName(cr, "call", cb)
    }
}
