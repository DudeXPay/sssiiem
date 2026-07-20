package com.simemu.spoof.hooks

import android.telephony.SubscriptionInfo
import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.utils.Reflect
import com.simemu.spoof.utils.XLog
import de.robv.android.xposed.XC_MethodHook

/**
 * Hooks [android.telephony.SubscriptionManager]. This is the modern, multi-SIM
 * source of truth. Apps enumerate active subscriptions here and read per-sub
 * carrier data; messaging apps pick which sub to send from via the default*
 * getters. With no physical SIM these all return empty/-1, so we synthesise a
 * subscription per profile (=> Dual-SIM / multi-SIM / eSIM all fall out for free).
 */
object SubscriptionManagerHook {

    fun install(cls: Class<*>, config: ProfileConfig) {
        val active = config.activeProfiles()
        val infos: List<SubscriptionInfo> =
            active.mapNotNull { SubscriptionInfoFactory.build(it) }
        if (infos.isEmpty()) {
            XLog.e("SubscriptionManager: could not fabricate any SubscriptionInfo")
            return
        }
        val subIds: IntArray = active.map { it.subscriptionId }.toIntArray()

        // getActiveSubscriptionInfoList(): the full list of active subs.
        replace(cls, "getActiveSubscriptionInfoList") { infos }
        // getActiveSubscriptionInfoCount(): apps use this before iterating.
        replace(cls, "getActiveSubscriptionInfoCount") { infos.size }
        // getAllSubscriptionInfoList() / getAvailableSubscriptionInfoList(): broader
        // enumerations some apps prefer; serve the same set.
        replace(cls, "getAllSubscriptionInfoList") { infos }
        replace(cls, "getAvailableSubscriptionInfoList") { infos }

        // getActiveSubscriptionInfo(subId): resolve by the requested subId.
        Reflect.hookAllByName(cls, "getActiveSubscriptionInfo", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val subId = param.args.firstOrNull { it is Int } as? Int
                param.result = infos.firstOrNull { it.subscriptionId == subId } ?: infos.first()
            }
        })
        // getActiveSubscriptionInfoForSimSlotIndex(slot): resolve by slot.
        Reflect.hookAllByName(cls, "getActiveSubscriptionInfoForSimSlotIndex",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val slot = param.args.firstOrNull { it is Int } as? Int ?: 0
                    param.result = infos.firstOrNull { it.simSlotIndex == slot } ?: infos.first()
                }
            })
        // getActiveSubscriptionIdList()/getActiveSubscriptionInfoIdList: raw id array.
        replace(cls, "getActiveSubscriptionIdList") { subIds }
        replace(cls, "getSubscriptionIds") { subIds }

        // ---- default subscription selectors ----
        // getDefaultSubscriptionId(): the system default sub.
        replace(cls, "getDefaultSubscriptionId") { config.defaultVoiceSubId }
        // getDefaultDataSubscriptionId(): which sub carries mobile data.
        replace(cls, "getDefaultDataSubscriptionId") { config.defaultDataSubId }
        // getDefaultVoiceSubscriptionId(): which sub places calls.
        replace(cls, "getDefaultVoiceSubscriptionId") { config.defaultVoiceSubId }
        // getDefaultSmsSubscriptionId(): which sub sends SMS (used by SMS apps).
        replace(cls, "getDefaultSmsSubscriptionId") { config.defaultSmsSubId }

        // isActiveSubscriptionId / isActiveSubId: true for ids we own.
        Reflect.hookAllByName(cls, "isActiveSubscriptionId", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val subId = param.args.firstOrNull { it is Int } as? Int
                param.result = subId != null && subId in subIds
            }
        })

        XLog.d("SubscriptionManager hooks installed (${infos.size} sub(s))")
    }

    private fun replace(cls: Class<*>, name: String, supplier: () -> Any?) {
        Reflect.hookAllByName(cls, name, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = supplier()
            }
        })
    }
}
