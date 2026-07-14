package com.simemu.spoof.utils

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Reflection helpers used across the hooks.
 *
 * Why we need "hook every overload by name": TelephonyManager exposes many
 * getters in several shapes across API 29..34 — e.g. getSimState(),
 * getSimState(int slotIndex); getSubscriberId(), getSubscriberId(int subId).
 * We rarely care about the arguments, only about forcing the return value, so
 * we bind ALL methods of a given name in one shot. This also future-proofs us
 * against overloads added in newer Android releases.
 */
object Reflect {

    /**
     * Hook every declared method named [methodName] on [clazz] (all overloads).
     * Returns the number of methods hooked.
     */
    fun hookAllByName(clazz: Class<*>, methodName: String, callback: XC_MethodHook): Int {
        var count = 0
        clazz.declaredMethods
            .filter { it.name == methodName && !Modifier.isAbstract(it.modifiers) }
            .forEach { m ->
                try {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, callback)
                    count++
                } catch (t: Throwable) {
                    XLog.e("Failed hooking ${clazz.simpleName}.$methodName", t)
                }
            }
        return count
    }

    /** Reflectively find a class or null (for optional / hidden classes). */
    fun classOrNull(name: String, cl: ClassLoader?): Class<*>? = try {
        Class.forName(name, false, cl)
    } catch (_: Throwable) { null }

    /** Read a private field, walking up the superclass chain. */
    fun getField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        return null
    }

    fun Method.returns(type: Class<*>): Boolean = returnType == type
}
