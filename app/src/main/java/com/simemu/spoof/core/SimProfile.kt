package com.simemu.spoof.core

import kotlinx.serialization.Serializable

/**
 * One emulated SIM. Every field maps directly to a value some TelephonyManager /
 * SubscriptionManager getter returns. Multiple profiles in one config = Dual SIM /
 * multi-SIM. `isEmbedded = true` marks an eSIM.
 *
 * All values are user-editable in the settings UI and serialized to JSON so they
 * survive reboots and can be imported/exported.
 */
@Serializable
data class SimProfile(
    var id: String = java.util.UUID.randomUUID().toString(),
    var label: String = "Profile",

    // ---- Identity / carrier ----
    var carrierName: String = "Test Mobile",       // getSimOperatorName / getNetworkOperatorName
    var carrierId: Int = 1435,                      // getCarrierId
    var mcc: String = "310",                        // Mobile Country Code
    var mnc: String = "260",                        // Mobile Network Code
    var countryIso: String = "us",                  // getSimCountryIso / getNetworkCountryIso

    // ---- Subscriber ----
    var phoneNumber: String = "+15555550100",       // getLine1Number
    var iccid: String = "8910390000005555010",      // SIM serial / ICCID
    var imsi: String = "310260000005555",           // getSubscriberId (IMSI)
    var simSerial: String = "8910390000005555010",  // getSimSerialNumber

    // ---- Subscription slot ----
    var subscriptionId: Int = 1,                    // stable per-SIM subId
    var slotIndex: Int = 0,                         // 0 = SIM1, 1 = SIM2 ...
    var isEmbedded: Boolean = false,                // true => eSIM

    // GSM = 1, CDMA = 2. Nearly all modern SIMs are GSM.
    var phoneType: Int = 1
) {
    /** MCC+MNC concatenated — the numeric operator string (e.g. "310260"). */
    val operatorNumeric: String get() = mcc + mnc
}
