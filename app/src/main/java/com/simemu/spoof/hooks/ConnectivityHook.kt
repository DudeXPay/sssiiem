package com.simemu.spoof.hooks

import android.net.NetworkCapabilities
import android.provider.Settings
import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.utils.Reflect
import com.simemu.spoof.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method

/**
 * Makes the *active cellular network* look like it belongs to the added data SIM.
 *
 * Login-vs-data checks often go through ConnectivityManager, not TelephonyManager:
 * they read [NetworkCapabilities.getSubscriptionIds] or a
 * [android.net.TelephonyNetworkSpecifier] off the active network. Without this
 * those still report the physical card's subId.
 *
 * Also rewrites Settings.Global `multi_sim_data_call` (and voice/sms) which some
 * dual-SIM apps read as "which SIM has data".
 */
object ConnectivityHook {

    fun install(cl: ClassLoader, config: ProfileConfig) {
        val dataSub = config.dataSubId()
        hookSubscriptionIds(NetworkCapabilities::class.java, dataSub)
        hookTelephonySpecifier(cl, dataSub)
        hookMultiSimSettings(config)
        XLog.d("Connectivity hooks installed (data sub=$dataSub)")
    }

    private fun hookSubscriptionIds(cls: Class<*>, dataSub: Int) {
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val rt = (param.method as Method).returnType
                param.result = when {
                    rt.isArray -> intArrayOf(dataSub)
                    Set::class.java.isAssignableFrom(rt) -> hashSetOf(dataSub)
                    else -> hashSetOf(dataSub)
                }
            }
        }
        Reflect.hookAllByName(cls, "getSubscriptionIds", cb)
        Reflect.hookAllByName(cls, "getSubIds", cb)
    }

    private fun hookTelephonySpecifier(cl: ClassLoader, dataSub: Int) {
        Reflect.classOrNull("android.net.TelephonyNetworkSpecifier", cl)?.let { spec ->
            Reflect.hookAllByName(spec, "getSubscriptionId", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = dataSub
                }
            })
        }
    }

    private fun hookMultiSimSettings(config: ProfileConfig) {
        val rewrite = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.firstOrNull { it is String } as? String ?: return
                when (name) {
                    "multi_sim_data_call" -> param.result = config.dataSubId()
                    "multi_sim_voice_call" -> param.result = config.defaultVoiceSubId
                    "multi_sim_sms" -> param.result = config.defaultSmsSubId
                }
            }
        }
        Reflect.hookAllByName(Settings.Global::class.java, "getInt", rewrite)
        Reflect.hookAllByName(Settings.Global::class.java, "getString", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.firstOrNull { it is String } as? String ?: return
                when (name) {
                    "multi_sim_data_call" -> param.result = config.dataSubId().toString()
                    "multi_sim_voice_call" -> param.result = config.defaultVoiceSubId.toString()
                    "multi_sim_sms" -> param.result = config.defaultSmsSubId.toString()
                }
            }
        })
    }
}
