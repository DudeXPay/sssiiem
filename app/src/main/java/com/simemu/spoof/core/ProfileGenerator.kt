package com.simemu.spoof.core

import kotlin.random.Random

/**
 * Produces plausible, internally-consistent random SIM profiles for quick test
 * setups. "Consistent" matters: IMSI must begin with MCC+MNC, the ICCID must
 * carry a valid Luhn check digit, etc., or carrier-validation apps reject them.
 */
object ProfileGenerator {

    // A few real MCC/MNC pairs so generated SIMs look like known carriers.
    private data class Carrier(val name: String, val mcc: String, val mnc: String,
                               val iso: String, val carrierId: Int, val cc: String)

    private val CARRIERS = listOf(
        Carrier("T-Mobile", "310", "260", "us", 1, "1"),
        Carrier("AT&T", "310", "410", "us", 1187, "1"),
        Carrier("Verizon", "311", "480", "us", 1839, "1"),
        Carrier("Vodafone UK", "234", "15", "gb", 1443, "44"),
        Carrier("O2", "234", "10", "gb", 1591, "44"),
        Carrier("Airtel", "404", "10", "in", 2119, "91"),
        Carrier("Jio", "405", "857", "in", 2118, "91"),
        Carrier("Telstra", "505", "01", "au", 1466, "61"),
    )

    fun random(slotIndex: Int = 0, subscriptionId: Int = slotIndex + 1): SimProfile {
        val c = CARRIERS.random()

        // IMSI = MCC(3) + MNC(2-3) + MSIN, total 15 digits.
        val msinLen = 15 - c.mcc.length - c.mnc.length
        val imsi = c.mcc + c.mnc + randomDigits(msinLen)

        // ICCID: 89 (telecom) + country + issuer + account, 19 digits + Luhn = 20.
        val iccidBody = "89" + c.cc.padStart(2, '0') + randomDigits(15)
        val iccid = iccidBody + luhnCheckDigit(iccidBody)

        val phone = "+" + c.cc + randomDigits(10)

        return SimProfile(
            label = "${c.name} (random)",
            carrierName = c.name,
            carrierId = c.carrierId,
            mcc = c.mcc,
            mnc = c.mnc,
            countryIso = c.iso,
            phoneNumber = phone,
            iccid = iccid,
            imsi = imsi,
            simSerial = iccid,
            subscriptionId = subscriptionId,
            slotIndex = slotIndex,
            isEmbedded = Random.nextBoolean(),
            phoneType = 1
        )
    }

    private fun randomDigits(n: Int): String =
        buildString { repeat(n) { append(Random.nextInt(10)) } }

    /** Standard Luhn check digit for a numeric string. */
    private fun luhnCheckDigit(number: String): Int {
        var sum = 0
        var alt = true // position immediately right of the body doubles first
        for (i in number.indices.reversed()) {
            var d = number[i].digitToInt()
            if (alt) { d *= 2; if (d > 9) d -= 9 }
            sum += d
            alt = !alt
        }
        return (10 - (sum % 10)) % 10
    }
}
