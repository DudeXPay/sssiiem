package com.simemu.spoof.hooks

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
        val infos = ArrayList(active.mapNotNull { SubscriptionInfoFactory.build(it) })
        if (infos.isEmpty()) {
            XLog.e("SubscriptionManager: could not fabricate any SubscriptionInfo")
            return
        }
        val subIds: IntArray = active.map { it.subscriptionId }.toIntArray()
        val dataSub = config.dataSubId()

        // getActiveSubscriptionInfoList(): the full list of active subs.
        replace(cls, "getActiveSubscriptionInfoList") { infos }
        // getActiveSubscriptionInfoCount(): apps use this before iterating.
        replace(cls, "getActiveSubscriptionInfoCount") { infos.size }
        // Broader enumerations some apps prefer; same added-SIM set so the
        // physical card never appears.
        replace(cls, "getAllSubscriptionInfoList") { infos }
        replace(cls, "getAvailableSubscriptionInfoList") { infos }
        replace(cls, "getCompleteActiveSubscriptionInfoList") { infos }
        replace(cls, "getAccessibleSubscriptionInfoList") { infos }

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

        // ---- default + active data selectors ----
        // Login-vs-data checks compare these to the login MSISDN's subId. They
        // must all be the added SIM, never the physical card's subId.
        replace(cls, "getDefaultSubscriptionId") { config.defaultVoiceSubId }
        replace(cls, "getDefaultDataSubscriptionId") { dataSub }
        replace(cls, "getActiveDataSubscriptionId") { dataSub }
        replace(cls, "getDefaultVoiceSubscriptionId") { config.defaultVoiceSubId }
        replace(cls, "getDefaultSmsSubscriptionId") { config.defaultSmsSubId }

        // getPhoneNumber(subId): API 33+ MSISDN lookup used at login.
        Reflect.hookAllByName(cls, "getPhoneNumber", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val subId = param.args.firstOrNull { it is Int } as? Int
                val profile = subId?.let { config.profileForSub(it) } ?: config.dataProfile()
                param.result = profile?.phoneNumber.orEmpty()
            }
        })

        // isActiveSubscriptionId / isActiveSubId: true for ids we own.
        val isActive = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val subId = param.args.firstOrNull { it is Int } as? Int
                param.result = subId != null && subId in subIds
            }
        }
        Reflect.hookAllByName(cls, "isActiveSubscriptionId", isActive)
        Reflect.hookAllByName(cls, "isActiveSubId", isActive)

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
