package com.simemu.spoof.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.simemu.spoof.R
import com.simemu.spoof.core.ConfigStore
import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.core.ProfileGenerator
import com.simemu.spoof.core.SimProfile
import com.simemu.spoof.databinding.ActivityMainBinding

/**
 * Home screen: master toggle (status card), profile list with empty state, and the
 * config actions (add via FAB; random / scope / import / export via the app bar).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: ConfigStore
    private lateinit var config: ProfileConfig
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        store = ConfigStore.get(this)
        config = store.load()

        b.switchMaster.isChecked = store.masterEnabled
        updateMasterStatus(store.masterEnabled)
        b.switchMaster.setOnCheckedChangeListener { _, checked ->
            // Master flag is stored under its own key; do NOT resave the whole
            // config here or a stale snapshot could clobber profile/scope edits.
            store.masterEnabled = checked
            updateMasterStatus(checked)
        }

        adapter = ProfileAdapter(config.profiles, { openEditor(it.id) }, ::onToggleActive)
        b.listProfiles.layoutManager = LinearLayoutManager(this)
        b.listProfiles.adapter = adapter

        b.fabAdd.setOnClickListener { openEditor(null) }
    }

    override fun onResume() {
        super.onResume()
        config = store.load()
        adapter.update(config.profiles)
        refreshList()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scope -> startActivity(Intent(this, ScopeActivity::class.java))
            R.id.action_random -> {
                config = store.load()   // freshest before mutating
                val slot = config.profiles.size
                config.profiles.add(ProfileGenerator.random(slotIndex = slot, subscriptionId = slot + 1))
                persist()
                Snackbar.make(b.root, "Random profile added", Snackbar.LENGTH_SHORT).show()
            }
            R.id.action_import -> pickImport.launch(arrayOf("application/json", "text/*"))
            R.id.action_export -> createExport.launch("sim_profiles.json")
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun updateMasterStatus(on: Boolean) {
        b.txtMasterStatus.setText(if (on) R.string.master_on_sub else R.string.master_off_sub)
    }

    /** Turn a SIM on/off, enforcing the MAX_ACTIVE (2) limit. Returns false if rejected. */
    private fun onToggleActive(profile: SimProfile, checked: Boolean): Boolean {
        val cfg = store.load()
        val target = cfg.profiles.firstOrNull { it.id == profile.id } ?: return false
        if (checked && cfg.activeCount() >= ProfileConfig.MAX_ACTIVE) {
            Snackbar.make(
                b.root,
                "Only ${ProfileConfig.MAX_ACTIVE} SIMs can be active at once — turn one off first",
                Snackbar.LENGTH_SHORT
            ).show()
            return false
        }
        target.active = checked
        store.save(cfg)
        config = cfg
        adapter.update(config.profiles)
        refreshList()
        return true
    }

    private fun openEditor(profileId: String?) {
        startActivity(Intent(this, ProfileEditActivity::class.java)
            .putExtra(ProfileEditActivity.EXTRA_ID, profileId))
    }

    private fun persist() {
        store.save(config)
        adapter.update(config.profiles)
        refreshList()
    }

    private fun refreshList() {
        val empty = config.profiles.isEmpty()
        b.emptyState.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        b.listProfiles.visibility = if (empty) android.view.View.GONE else android.view.View.VISIBLE
        val total = resources.getQuantityString(
            R.plurals.profile_count, config.profiles.size, config.profiles.size)
        b.txtProfileCount.text = "$total · ${config.activeCount()}/${ProfileConfig.MAX_ACTIVE} active"
    }

    // ---- Import / Export via Storage Access Framework ----

    private val pickImport = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            config = ProfileConfig.fromJson(text)
            persist()
            toast("Imported ${config.profiles.size} profile(s)")
        }.onFailure { toast("Import failed: ${it.message}") }
    }

    private val createExport = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(config.toJson()) }
            toast("Exported")
        }.onFailure { toast("Export failed: ${it.message}") }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
