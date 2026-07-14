package com.simemu.spoof.hooks

import android.telephony.SubscriptionInfo
import com.simemu.spoof.core.SimProfile
import com.simemu.spoof.utils.XLog

/**
 * Builds a real [SubscriptionInfo] instance from a [SimProfile].
 *
 * WHY THIS IS HARD: SubscriptionInfo has no public constructor and its private
 * constructor signature changed repeatedly between Android 10 and 16 (mcc/mnc
 * went int -> String, mCarrierId / mProfileClass were added, etc.). Matching a
 * constructor positionally is fragile.
 *
 * INSTEAD we allocate the object WITHOUT invoking any constructor (via Unsafe)
 * and then set only the private fields we know by name. Missing fields keep
 * their JVM defaults, which is fine — apps read via getters, and the getters we
 * care about just return these fields. Field name variants (mMcc vs mMccString)
 * are both attempted, so one code path works across all supported API levels.
 */
object SubscriptionInfoFactory {

    private val unsafe: Any? by lazy {
        try {
            val c = Class.forName("sun.misc.Unsafe")
            val f = c.getDeclaredField("theUnsafe").apply { isAccessible = true }
            f.get(null)
        } catch (t: Throwable) {
            XLog.e("Unsafe unavailable", t); null
        }
    }

    private val allocateInstance by lazy {
        unsafe?.javaClass?.getMethod("allocateInstance", Class::class.java)
    }

    fun build(p: SimProfile): SubscriptionInfo? {
        val u = unsafe ?: return null
        val alloc = allocateInstance ?: return null
        return try {
            val info = alloc.invoke(u, SubscriptionInfo::class.java) as SubscriptionInfo
            setField(info, "mId", p.subscriptionId)
            setField(info, "mIccId", p.iccid)
            setField(info, "mSimSlotIndex", p.slotIndex)
            setField(info, "mDisplayName", p.carrierName)
            setField(info, "mCarrierName", p.carrierName)
            setField(info, "mNumber", p.phoneNumber)
            setField(info, "mCountryIso", p.countryIso)
            setField(info, "mIsEmbedded", p.isEmbedded)
            setField(info, "mCarrierId", p.carrierId)

            // mcc/mnc field names AND types vary by version (mMcc int, mMccString
            // String, or mMcc String on Android 13+). Scan every field and set any
            // whose name contains mcc/mnc, choosing the value by the field's type.
            setMccMnc(info, p.mcc, p.mnc)

            // Card identifiers used by newer eSIM-aware code paths.
            setField(info, "mCardString", p.iccid)
            setField(info, "mCardId", p.slotIndex)

            // profileClass 1 = PROVISIONING/embedded-ish, subscriptionType 0 = local SIM.
            setField(info, "mProfileClass", if (p.isEmbedded) 2 else -1)
            setField(info, "mSubscriptionType", 0)
            info
        } catch (t: Throwable) {
            XLog.e("build SubscriptionInfo failed", t)
            null
        }
    }

    /**
     * Set mcc/mnc across version differences: walk all declared fields (and
     * superclasses) and assign any field whose name contains "mcc"/"mnc",
     * matching the value to the field's declared type (String vs int).
     */
    private fun setMccMnc(target: Any, mcc: String, mnc: String) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                val n = f.name.lowercase()
                val isMcc = n.contains("mcc")
                val isMnc = n.contains("mnc")
                if (!isMcc && !isMnc) continue
                val raw = if (isMcc) mcc else mnc
                try {
                    f.isAccessible = true
                    when (f.type) {
                        String::class.java -> f.set(target, raw)
                        Int::class.javaPrimitiveType, Integer::class.java ->
                            f.set(target, raw.toIntOrNull() ?: 0)
                    }
                } catch (_: Throwable) { /* ignore incompatible field */ }
            }
            c = c.superclass
        }
    }

    /** Set a private field if it exists; silently ignore if this API level lacks it. */
    private fun setField(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (t: Throwable) {
                return
            }
        }
    }
}
