package com.simemu.spoof.hooks

import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.utils.Reflect
import com.simemu.spoof.utils.XLog
import de.robv.android.xposed.XC_MethodHook

/**
 * ADVANCED: intercept the telephony Binder proxies directly.
 *
 * Some apps (and obfuscated SDKs) skip TelephonyManager and talk to the AIDL
 * interfaces returned by ServiceManager.getService(): IPhoneSubInfo, ITelephony,
 * ISub. Inside the app process these are `...$Stub$Proxy` classes. Because
 * TelephonyManager itself calls through these proxies, hooking here is a second
 * safety net AND covers the direct-AIDL bypass.
 *
 * These interfaces are internal and their method signatures drift between
 * versions, so everything here is best-effort: we look the classes up
 * reflectively, hook getters by name (all overloads), and no-op if absent.
 */
object BinderHook {

    fun install(cl: ClassLoader, config: ProfileConfig) {
        if (config.primary() == null) return

        // ---- IPhoneSubInfo: subscriber identity (IMSI, ICCID, MSISDN) ----
        Reflect.classOrNull("com.android.internal.telephony.IPhoneSubInfo\$Stub\$Proxy", cl)
            ?.let { proxy ->
                bindBySub(proxy, "getSubscriberId", config) { it.imsi }
                bindBySub(proxy, "getSubscriberIdForSubscriber", config) { it.imsi }
                bindBySub(proxy, "getIccSerialNumber", config) { it.iccid }
                bindBySub(proxy, "getIccSerialNumberForSubscriber", config) { it.iccid }
                bindBySub(proxy, "getLine1Number", config) { it.phoneNumber }
                bindBySub(proxy, "getLine1NumberForSubscriber", config) { it.phoneNumber }
                XLog.d("Hooked IPhoneSubInfo proxy")
            }

        // ---- ITelephony: SIM state & operator numeric ----
        Reflect.classOrNull("com.android.internal.telephony.ITelephony\$Stub\$Proxy", cl)
            ?.let { proxy ->
                // SIM_STATE_READY == 5. Covers getSimState / getSimStateForSlotIndex.
                bind(proxy, "getSimState") { 5 }
                bind(proxy, "getSimStateForSlotIndex") { 5 }
                XLog.d("Hooked ITelephony proxy")
            }

        // ---- ISub: default subscription ids ----
        Reflect.classOrNull("com.android.internal.telephony.ISub\$Stub\$Proxy", cl)
            ?.let { proxy ->
                bind(proxy, "getDefaultSubId") { config.defaultVoiceSubId }
                bind(proxy, "getDefaultDataSubId") { config.defaultDataSubId }
                bind(proxy, "getDefaultVoiceSubId") { config.defaultVoiceSubId }
                bind(proxy, "getDefaultSmsSubId") { config.defaultSmsSubId }
                XLog.d("Hooked ISub proxy")
            }
    }

    private fun bind(cls: Class<*>, name: String, supplier: () -> Any?) {
        Reflect.hookAllByName(cls, name, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = supplier() }
        })
    }

    private fun bindBySub(
        cls: Class<*>, name: String, config: ProfileConfig,
        supplier: (com.simemu.spoof.core.SimProfile) -> Any?
    ) {
        Reflect.hookAllByName(cls, name, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val subId = param.args?.firstOrNull { it is Int } as? Int
                val profile = subId?.let { config.profileForSub(it) } ?: config.primary()
                param.result = profile?.let(supplier)
            }
        })
    }
}
