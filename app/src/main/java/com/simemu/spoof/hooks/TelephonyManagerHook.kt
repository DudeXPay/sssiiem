package com.simemu.spoof.hooks

import android.telephony.TelephonyManager
import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.core.SimProfile
import com.simemu.spoof.utils.Reflect
import com.simemu.spoof.utils.XLog
import de.robv.android.xposed.XC_MethodHook

/**
 * Hooks [TelephonyManager]. This is the API 95% of apps use to ask "is there a
 * SIM and whose is it?" We force each getter to report the active profile.
 *
 * Strategy: for each getter we bind ALL overloads by name (see Reflect) and set
 * the result in `beforeHookedMethod`, which short-circuits the real platform
 * call. That matters because with no physical SIM the real methods return null /
 * SIM_STATE_ABSENT / throw — we must not let them run.
 *
 * When an overload takes a slotIndex/subId we honour it so Dual-SIM apps see the
 * correct per-slot values; otherwise we serve the primary profile.
 */
object TelephonyManagerHook {

    fun install(cls: Class<*>, config: ProfileConfig) {
        val primary = config.primary() ?: return

        // ---- SIM presence & state ----
        // getSimState()/getSimState(slot): apps gate ALL telephony behaviour on
        // this. Must be SIM_STATE_READY (5) or the app treats the SIM as absent.
        replace(cls, "getSimState") { TelephonyManager.SIM_STATE_READY }
        // hasIccCard(): the simplest "is a SIM physically present?" check.
        replace(cls, "hasIccCard") { true }
        // getSimCardState()/getIccCardState (newer): card-present at the eUICC level.
        replace(cls, "getSimCardState") { TelephonyManager.SIM_STATE_READY }

        // ---- Subscriber identity ----
        // getSubscriberId(): the IMSI. Uniquely identifies the subscriber; carrier
        // apps use it for provisioning / anti-fraud.
        replaceBySub(cls, "getSubscriberId", config) { it.imsi }
        // getSimSerialNumber(): the ICCID printed on the SIM.
        replaceBySub(cls, "getSimSerialNumber", config) { it.iccid }
        // getLine1Number(): the MSISDN (phone number). Deprecated but widely read.
        replaceBySub(cls, "getLine1Number", config) { it.phoneNumber }

        // ---- SIM (home) operator ----
        // getSimOperator(): the numeric MCC+MNC of the SIM's home network.
        replaceBySub(cls, "getSimOperator", config) { it.operatorNumeric }
        // getSimOperatorName(): human-readable home carrier (SPN).
        replaceBySub(cls, "getSimOperatorName", config) { it.carrierName }
        // getSimCountryIso(): ISO country of the SIM's home network.
        replaceBySub(cls, "getSimCountryIso", config) { it.countryIso }

        // ---- Network (serving) operator ----
        // getNetworkOperator(): numeric MCC+MNC of the currently registered network.
        replace(cls, "getNetworkOperator") { primary.operatorNumeric }
        // getNetworkOperatorName(): registered network's display name.
        replace(cls, "getNetworkOperatorName") { primary.carrierName }
        // getNetworkCountryIso(): ISO country of the registered network.
        replace(cls, "getNetworkCountryIso") { primary.countryIso }

        // ---- Radio / phone type ----
        // getPhoneType(): GSM(1)/CDMA(2)/NONE(0). Apps branch UMTS vs CDMA on this.
        replace(cls, "getPhoneType") { primary.phoneType }

        // ---- Carrier identification (API 28+/29+) ----
        // getCarrierId(): Android's canonical numeric carrier id resolved from the
        // SIM. Used by CarrierConfig, RCS, VoLTE feature gating.
        replace(cls, "getCarrierId") { primary.carrierId }
        // getSimCarrierId(): same idea, SIM-scoped alias present on some versions.
        replace(cls, "getSimCarrierId") { primary.carrierId }
        // getCarrierIdFromSimMccMnc(): resolves a carrier id purely from MCC/MNC.
        replace(cls, "getCarrierIdFromSimMccMnc") { primary.carrierId }
        // getSimCarrierIdName(): display name backing the carrier id.
        replace(cls, "getSimCarrierIdName") { primary.carrierName }
        // getSimSpecificCarrierId()/Name(): fine-grained carrier id (MVNO-aware).
        replace(cls, "getSimSpecificCarrierId") { primary.carrierId }
        replace(cls, "getSimSpecificCarrierIdName") { primary.carrierName }

        // ---- Modem / slot counts ----
        // Some apps decide "is there a SIM / how many" from these, not getSimState.
        val simCount = config.profiles.size.coerceAtLeast(1)
        // getPhoneCount()/getActiveModemCount()/getSupportedModemCount(): slot counts.
        replace(cls, "getPhoneCount") { simCount }
        replace(cls, "getActiveModemCount") { simCount }
        replace(cls, "getSupportedModemCount") { simCount }
        // isVoiceCapable()/isSmsCapable(): gate messaging/dialer features.
        replace(cls, "isVoiceCapable") { true }
        replace(cls, "isSmsCapable") { true }

        // ---- Network registration / radio ----
        // Apps that check "is the SIM registered on a network" read these. With no
        // real radio they report out-of-service; we present a registered LTE network.
        // isNetworkRoaming(): false so apps don't gate on roaming.
        replace(cls, "isNetworkRoaming") { false }
        // getNetworkType()/getDataNetworkType()/getVoiceNetworkType(): 13 = LTE.
        replace(cls, "getNetworkType") { NETWORK_TYPE_LTE }
        replace(cls, "getDataNetworkType") { NETWORK_TYPE_LTE }
        replace(cls, "getVoiceNetworkType") { NETWORK_TYPE_LTE }
        // getServiceState(): many apps require STATE_IN_SERVICE before treating the
        // SIM as usable. We synthesise an in-service ServiceState for the carrier.
        Reflect.hookAllByName(cls, "getServiceState", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = buildServiceState(primary)
            }
        })

        XLog.d("TelephonyManager hooks installed on ${cls.name}")
    }

    private const val NETWORK_TYPE_LTE = 13

    /** Build an in-service ServiceState for the profile, or null if unsupported. */
    private fun buildServiceState(p: SimProfile): Any? = try {
        val ss = android.telephony.ServiceState()
        @Suppress("DEPRECATION")
        ss.setState(android.telephony.ServiceState.STATE_IN_SERVICE)
        // setOperatorName is @hide; set reflectively (long, short, numeric).
        runCatching {
            val m = ss.javaClass.getMethod(
                "setOperatorName", String::class.java, String::class.java, String::class.java)
            m.invoke(ss, p.carrierName, p.carrierName, p.operatorNumeric)
        }
        runCatching {
            ss.javaClass.getMethod("setRoaming", Boolean::class.javaPrimitiveType)
                .invoke(ss, false)
        }
        ss
    } catch (t: Throwable) {
        XLog.e("buildServiceState failed", t); null
    }

    // -- helpers -------------------------------------------------------------

    /** Bind all overloads of [name]; result is a constant supplier. */
    private fun replace(cls: Class<*>, name: String, supplier: () -> Any?) {
        val n = Reflect.hookAllByName(cls, name, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = supplier()
            }
        })
        if (n == 0) XLog.d("no method named $name on ${cls.simpleName}")
    }

    /**
     * Bind all overloads of [name], choosing the profile by the subId argument
     * when the overload carries one (so Dual-SIM getters resolve per-slot).
     */
    private fun replaceBySub(
        cls: Class<*>, name: String, config: ProfileConfig,
        supplier: (SimProfile) -> Any?
    ) {
        Reflect.hookAllByName(cls, name, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val profile = pickProfile(param.args, config)
                param.result = profile?.let(supplier)
            }
        })
    }

    /** If the first int arg looks like a subId we recognise, use that profile. */
    private fun pickProfile(args: Array<Any?>?, config: ProfileConfig): SimProfile? {
        val subArg = args?.firstOrNull { it is Int } as? Int
        return subArg?.let { config.profileForSub(it) } ?: config.primary()
    }
}
