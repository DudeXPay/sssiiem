package com.simemu.spoof.hooks

import android.database.MatrixCursor
import android.net.Uri
import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.core.SimProfile
import com.simemu.spoof.utils.Reflect
import com.simemu.spoof.utils.XLog
import de.robv.android.xposed.XC_MethodHook

/**
 * Spoofs `content://telephony/siminfo` (and close cousins). Many apps enumerate
 * SIMs through ContentResolver instead of SubscriptionManager, which would
 * otherwise still list the physical card and break "same SIM for login and data".
 */
object TelephonyProviderHook {

    private val DEFAULT_COLS = arrayOf(
        "_id", "icc_id", "sim_id", "display_name", "carrier_name", "number",
        "mcc", "mnc", "mcc_string", "mnc_string", "sim_slot_index", "is_embedded",
        "iso", "card_id", "carrier_id", "data_roaming", "name_source"
    )

    fun install(config: ProfileConfig) {
        val cr = android.content.ContentResolver::class.java
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uri = param.args.firstOrNull { it is Uri } as? Uri ?: return
                if (!isSimInfoUri(uri)) return
                @Suppress("UNCHECKED_CAST")
                val projection = param.args.getOrNull(1) as? Array<String>
                param.result = buildCursor(config, projection)
            }
        }
        Reflect.hookAllByName(cr, "query", cb)
        XLog.d("Telephony provider hooks installed")
    }

    private fun isSimInfoUri(uri: Uri): Boolean {
        val s = uri.toString().lowercase()
        if ("telephony" !in s) return false
        return "siminfo" in s || "subscriber" in s || "subscription" in s
    }

    private fun buildCursor(config: ProfileConfig, projection: Array<String>?): MatrixCursor {
        val cols = if (projection.isNullOrEmpty()) DEFAULT_COLS else projection
        val cursor = MatrixCursor(cols)
        for (p in config.activeProfiles()) {
            cursor.addRow(cols.map { valueFor(it, p) }.toTypedArray())
        }
        return cursor
    }

    private fun valueFor(col: String, p: SimProfile): Any? = when (col.lowercase()) {
        "_id", "sim_id" -> p.subscriptionId
        "icc_id", "icc_id_plain", "card_id", "card_string" -> p.iccid
        "display_name", "carrier_name" -> p.carrierName
        "number" -> p.phoneNumber
        "mcc" -> p.mcc.toIntOrNull() ?: 0
        "mnc" -> p.mnc.toIntOrNull() ?: 0
        "mcc_string" -> p.mcc
        "mnc_string" -> p.mnc
        "sim_slot_index", "slot_index" -> p.slotIndex
        "is_embedded" -> if (p.isEmbedded) 1 else 0
        "iso", "iso_country_code" -> p.countryIso
        "carrier_id" -> p.carrierId
        "data_roaming", "name_source" -> 0
        else -> null
    }
}
