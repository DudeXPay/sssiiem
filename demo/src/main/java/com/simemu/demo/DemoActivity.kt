package com.simemu.demo

import android.annotation.SuppressLint
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone verifier. Reads every value the SIM Emulator module hooks and prints
 * it to the screen AND to logcat (tag "SIMTEST"), so a test can be confirmed with:
 *
 *     adb logcat -s SIMTEST
 *
 * Scope THIS app in both LSPosed and the module. With the module ON you should see
 * simState=5 (READY), hasIccCard=true, and your profile's IMSI/operator/number.
 */
class DemoActivity : AppCompatActivity() {

    @SuppressLint("MissingPermission", "HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tm = getSystemService(TelephonyManager::class.java)
        val sm = getSystemService(SubscriptionManager::class.java)

        val sb = StringBuilder()
        fun line(k: String, v: Any?) {
            val s = "$k = $v"
            sb.appendLine(s)
            Log.i("SIMTEST", s)
        }

        line("--- TelephonyManager ---", "")
        safe { line("getSimState", tm.simState) }             // expect 5 = READY
        safe { line("hasIccCard", tm.hasIccCard()) }          // expect true
        safe { line("getSubscriberId", tm.subscriberId) }     // IMSI
        safe { line("getSimSerialNumber", tm.simSerialNumber) }
        safe { line("getLine1Number", tm.line1Number) }
        safe { line("getSimOperator", tm.simOperator) }
        safe { line("getSimOperatorName", tm.simOperatorName) }
        safe { line("getNetworkOperator", tm.networkOperator) }
        safe { line("getNetworkOperatorName", tm.networkOperatorName) }
        safe { line("getSimCountryIso", tm.simCountryIso) }
        safe { line("getNetworkCountryIso", tm.networkCountryIso) }
        safe { line("getPhoneType", tm.phoneType) }
        safe { line("getSimCarrierId", tm.simCarrierId) }
        safe { line("getSimCarrierIdName", tm.simCarrierIdName) }

        line("", "")
        line("--- SubscriptionManager ---", "")
        safe {
            val list = sm.activeSubscriptionInfoList
            line("activeSubscriptionInfoList.size", list?.size)
            list?.forEachIndexed { i, info ->
                line("  sub[$i]", "id=${info.subscriptionId} slot=${info.simSlotIndex} " +
                        "carrier=${info.carrierName} mcc=${info.mccString} mnc=${info.mncString} " +
                        "number=${info.number} iccid=${info.iccId} embedded=${info.isEmbedded}")
            }
        }
        safe { line("defaultSubscriptionId", SubscriptionManager.getDefaultSubscriptionId()) }
        safe { line("defaultDataSubscriptionId", SubscriptionManager.getDefaultDataSubscriptionId()) }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            safe { line("activeDataSubscriptionId", SubscriptionManager.getActiveDataSubscriptionId()) }
        }
        safe { line("defaultVoiceSubscriptionId", SubscriptionManager.getDefaultVoiceSubscriptionId()) }
        safe { line("defaultSmsSubscriptionId", SubscriptionManager.getDefaultSmsSubscriptionId()) }

        line("", "")
        line("--- telephony/siminfo (must be added SIM only) ---", "")
        safe {
            val uri = android.net.Uri.parse("content://telephony/siminfo")
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                line("siminfo.rows", c.count)
                val idCol = c.getColumnIndex("_id")
                val numCol = c.getColumnIndex("number")
                while (c.moveToNext()) {
                    val id = if (idCol >= 0) c.getString(idCol) else "?"
                    val num = if (numCol >= 0) c.getString(numCol) else "?"
                    line("  siminfo", "id=$id number=$num")
                }
            }
        }

        val tv = TextView(this).apply {
            text = sb.toString()
            textSize = 13f
            setPadding(24, 24, 24, 24)
            gravity = Gravity.START
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w("SIMTEST", "read failed: ${t.message}") }
    }
}
