package com.simemu.spoof.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The complete, serializable configuration. This single object is what gets
 * written to SharedPreferences as JSON and read back by the hooks.
 *
 * @property profiles   All emulated SIMs. size > 1 => Dual/Multi SIM.
 * @property scope      package name -> enabled?  Only apps mapped to `true` get
 *                      spoofed. An app absent from the map is NOT spoofed.
 * @property defaultVoiceSubId / DataSubId / SmsSubId  which subId the
 *                      SubscriptionManager default getters report.
 */
@Serializable
data class ProfileConfig(
    // Empty by default: a failed/blank read must NOT inject a phantom profile that a
    // later save could persist over real data. First run shows the empty state.
    var profiles: MutableList<SimProfile> = mutableListOf(),
    var scope: MutableMap<String, Boolean> = mutableMapOf(),
    var defaultVoiceSubId: Int = 1,
    var defaultDataSubId: Int = 1,
    var defaultSmsSubId: Int = 1,
    // When true, scoped apps log every telephony getter + telephony ContentResolver
    // access they make (see DiagnosticHook). For debugging detection gaps.
    var diagnostics: Boolean = false
) {
    fun isAppEnabled(pkg: String): Boolean = scope[pkg] == true

    /** The profile that owns a given subscription id, if any. */
    fun profileForSub(subId: Int): SimProfile? = profiles.firstOrNull { it.subscriptionId == subId }

    /** Profile shown for single-SIM getters (first active profile). */
    fun primary(): SimProfile? = profiles.firstOrNull()

    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true   // forward-compatible with older exports
            encodeDefaults = true
        }

        fun fromJson(raw: String?): ProfileConfig = try {
            if (raw.isNullOrBlank()) ProfileConfig() else JSON.decodeFromString(raw)
        } catch (t: Throwable) {
            ProfileConfig()
        }
    }
}
