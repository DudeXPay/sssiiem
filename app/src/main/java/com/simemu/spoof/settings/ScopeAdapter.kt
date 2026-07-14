package com.simemu.spoof.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simemu.spoof.databinding.ItemScopeBinding

/** Installed-app rows with an enable-in-scope switch, filterable by query. */
class ScopeAdapter(
    private val pm: PackageManager,
    private val allApps: List<ApplicationInfo>,
    private val isEnabled: (String) -> Boolean,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<ScopeAdapter.VH>() {

    // Cache labels once so filtering/binding stays smooth.
    private val labels: Map<String, String> =
        allApps.associate { it.packageName to pm.getApplicationLabel(it).toString() }

    private var shown: List<ApplicationInfo> = allApps

    inner class VH(val b: ItemScopeBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemScopeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = shown[position]
        val pkg = app.packageName
        holder.b.txtAppName.text = labels[pkg] ?: pkg
        holder.b.txtPkg.text = pkg
        holder.b.imgAppIcon.setImageDrawable(pm.getApplicationIcon(app))
        holder.b.swEnabled.setOnCheckedChangeListener(null)
        holder.b.swEnabled.isChecked = isEnabled(pkg)
        holder.b.swEnabled.setOnCheckedChangeListener { _, checked -> onToggle(pkg, checked) }
    }

    override fun getItemCount() = shown.size

    fun filter(query: String) {
        val q = query.trim().lowercase()
        shown = if (q.isEmpty()) allApps
        else allApps.filter {
            (labels[it.packageName]?.lowercase()?.contains(q) == true) ||
                it.packageName.lowercase().contains(q)
        }
        notifyDataSetChanged()
    }
}
