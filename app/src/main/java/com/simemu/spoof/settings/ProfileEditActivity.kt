package com.simemu.spoof.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simemu.spoof.R
import com.simemu.spoof.core.ConfigStore
import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.core.SimProfile
import com.simemu.spoof.databinding.ActivityProfileEditBinding

/** Edit (or create) a single SIM profile, grouped into labelled sections. */
class ProfileEditActivity : AppCompatActivity() {

    private lateinit var b: ActivityProfileEditBinding
    private lateinit var store: ConfigStore
    private lateinit var config: ProfileConfig
    private lateinit var profile: SimProfile
    private var isNew = false

    // Dropdown index -> phoneType value: GSM=1, CDMA=2, None=0.
    private val phoneTypeValues = intArrayOf(1, 2, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = ConfigStore.get(this)
        config = store.load()

        val id = intent.getStringExtra(EXTRA_ID)
        profile = config.profiles.firstOrNull { it.id == id } ?: run {
            isNew = true
            SimProfile(slotIndex = config.profiles.size, subscriptionId = config.profiles.size + 1)
        }
        supportActionBar?.setTitle(if (isNew) R.string.new_profile else R.string.edit_profile)

        setupSectionHeaders()
        bind()

        // Live "operator numeric" preview under the MCC/MNC row.
        b.edMcc.doAfterTextChanged { updateOperatorNumeric() }
        b.edMnc.doAfterTextChanged { updateOperatorNumeric() }

        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { confirmDelete() }
        // Hide delete for a profile that isn't saved yet.
        b.btnDelete.visibility = if (isNew) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun setupSectionHeaders() {
        b.hdrCarrier.txtSectionTitle.setText(R.string.sec_carrier)
        b.hdrCarrier.imgSectionIcon.setImageResource(R.drawable.ic_sim_card)
        b.hdrSubscriber.txtSectionTitle.setText(R.string.sec_subscriber)
        b.hdrSubscriber.imgSectionIcon.setImageResource(R.drawable.ic_badge)
        b.hdrSlot.txtSectionTitle.setText(R.string.sec_slot)
        b.hdrSlot.imgSectionIcon.setImageResource(R.drawable.ic_memory)
    }

    private fun bind() = with(b) {
        edLabel.setText(profile.label)
        edCarrierName.setText(profile.carrierName)
        edCarrierId.setText(profile.carrierId.toString())
        edMcc.setText(profile.mcc)
        edMnc.setText(profile.mnc)
        edCountryIso.setText(profile.countryIso)
        edPhone.setText(profile.phoneNumber)
        edIccid.setText(profile.iccid)
        edImsi.setText(profile.imsi)
        edSubId.setText(profile.subscriptionId.toString())
        edSlot.setText(profile.slotIndex.toString())
        swEmbedded.isChecked = profile.isEmbedded

        val items = resources.getStringArray(R.array.phone_types)
        val idx = phoneTypeValues.indexOf(profile.phoneType).let { if (it < 0) 0 else it }
        ddPhoneType.setText(items[idx], false)

        updateOperatorNumeric()
    }

    private fun updateOperatorNumeric() {
        val op = b.edMcc.text?.toString().orEmpty() + b.edMnc.text?.toString().orEmpty()
        b.txtOperatorNumeric.text = getString(R.string.operator_numeric_fmt, op.ifEmpty { "—" })
    }

    private fun selectedPhoneType(): Int {
        val items = resources.getStringArray(R.array.phone_types)
        val idx = items.indexOf(b.ddPhoneType.text?.toString()).let { if (it < 0) 0 else it }
        return phoneTypeValues[idx]
    }

    private fun save() = with(b) {
        profile.label = edLabel.text.toString().ifBlank { "Profile" }
        profile.carrierName = edCarrierName.text.toString()
        profile.carrierId = edCarrierId.text.toString().toIntOrNull() ?: 0
        profile.mcc = edMcc.text.toString()
        profile.mnc = edMnc.text.toString()
        profile.countryIso = edCountryIso.text.toString().lowercase()
        profile.phoneNumber = edPhone.text.toString()
        profile.iccid = edIccid.text.toString()
        profile.simSerial = edIccid.text.toString()
        profile.imsi = edImsi.text.toString()
        profile.subscriptionId = edSubId.text.toString().toIntOrNull() ?: 1
        profile.slotIndex = edSlot.text.toString().toIntOrNull() ?: 0
        profile.phoneType = selectedPhoneType()
        profile.isEmbedded = swEmbedded.isChecked

        // Reload latest, then insert/replace only THIS profile, so a concurrent
        // scope/master change elsewhere isn't reverted.
        val cfg = store.load()
        val idx = cfg.profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) cfg.profiles[idx] = profile else cfg.profiles.add(profile)
        store.save(cfg)
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete profile?")
            .setMessage("“${profile.label}” will be removed.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val cfg = store.load()
                cfg.profiles.removeAll { it.id == profile.id }
                store.save(cfg)
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "profile_id"
    }
}
