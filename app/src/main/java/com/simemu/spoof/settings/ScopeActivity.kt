package com.simemu.spoof.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.simemu.spoof.core.ConfigStore
import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.databinding.ActivityScopeBinding

/**
 * Per-app scope picker. Only apps toggled ON here get spoofed — the hook checks
 * this allow-list before installing. Layered on top of LSPosed's own scope.
 */
class ScopeActivity : AppCompatActivity() {

    private lateinit var b: ActivityScopeBinding
    private lateinit var store: ConfigStore
    private lateinit var config: ProfileConfig
    private lateinit var adapter: ScopeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScopeBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = ConfigStore.get(this)
        config = store.load()

        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    pm.getLaunchIntentForPackage(it.packageName) != null ||
                    config.scope.containsKey(it.packageName)
            }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        adapter = ScopeAdapter(
            pm = pm,
            allApps = apps,
            isEnabled = { pkg -> config.isAppEnabled(pkg) },
            onToggle = { pkg, checked ->
                // Reload the latest config before mutating so we only change the
                // scope map and never overwrite profile edits made elsewhere.
                config = store.load()
                if (checked) config.scope[pkg] = true else config.scope.remove(pkg)
                store.save(config)
            }
        )
        b.listApps.layoutManager = LinearLayoutManager(this)
        b.listApps.adapter = adapter

        b.edSearch.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }
    }
}
