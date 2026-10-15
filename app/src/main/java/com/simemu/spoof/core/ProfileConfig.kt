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

    /**
     * The SIMs actually presented to apps: only active profiles, capped at
     * MAX_ACTIVE (2) as a hardware-realistic safety net. Everything downstream
     * (TelephonyManager / SubscriptionManager hooks) reads from here.
     */
    fun activeProfiles(): List<SimProfile> = profiles.filter { it.active }.take(MAX_ACTIVE)

    /** How many profiles are currently switched on. */
    fun activeCount(): Int = profiles.count { it.active }

    /** The profile that owns a given subscription id, among active ones. */
    fun profileForSub(subId: Int): SimProfile? =
        activeProfiles().firstOrNull { it.subscriptionId == subId }

    /** Profile shown for single-SIM getters (first active profile). */
    fun primary(): SimProfile? = activeProfiles().firstOrNull()

    /** True if [slot] is occupied by an added active profile. */
    fun isActiveSlot(slot: Int): Boolean =
        activeProfiles().any { it.slotIndex == slot }

    /**
     * The subscription id scoped apps should treat as both the login SIM and
     * the mobile-data SIM. Falls back to the first active profile.
     */
    fun dataSubId(): Int =
        profileForSub(defaultDataSubId)?.subscriptionId
            ?: primary()?.subscriptionId
            ?: defaultDataSubId

    /** Profile that owns [dataSubId], i.e. the login + data SIM. */
    fun dataProfile(): SimProfile? = profileForSub(dataSubId()) ?: primary()

    /**
     * Keep default data/voice/SMS pointing at an *added* active SIM. Login vs
     * mobile-data checks in target apps fail if these still hold a real-card
     * subId (or a stale id from a deleted profile).
     */
    fun syncDefaultsFromActive() {
        val active = activeProfiles()
        if (active.isEmpty()) return
        val ids = active.map { it.subscriptionId }.toSet()
        val fallback = active.first().subscriptionId
        if (defaultDataSubId !in ids) defaultDataSubId = fallback
        if (defaultVoiceSubId !in ids) defaultVoiceSubId = defaultDataSubId
        if (defaultSmsSubId !in ids) defaultSmsSubId = defaultDataSubId
    }

    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        /** Max SIMs active at once (dual-SIM). */
        const val MAX_ACTIVE = 2

        val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true   // forward-compatible with older exports
            encodeDefaults = true
        }

        fun fromJson(raw: String?): ProfileConfig = try {
            val cfg = if (raw.isNullOrBlank()) ProfileConfig()
                else JSON.decodeFromString<ProfileConfig>(raw)
            cfg.syncDefaultsFromActive()
            cfg
        } catch (t: Throwable) {
            ProfileConfig()
        }
    }
}
