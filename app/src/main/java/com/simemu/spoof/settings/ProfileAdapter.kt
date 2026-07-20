package com.simemu.spoof.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simemu.spoof.core.SimProfile
import com.simemu.spoof.databinding.ItemProfileBinding

/**
 * @param onClick        open the editor for a profile
 * @param onToggleActive request to turn a profile on/off. Returns true if applied,
 *                       false if rejected (e.g. the 2-active limit) so the row
 *                       snaps back.
 */
class ProfileAdapter(
    private var items: List<SimProfile>,
    private val onClick: (SimProfile) -> Unit,
    private val onToggleActive: (SimProfile, Boolean) -> Boolean
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    inner class VH(val b: ItemProfileBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.b.txtLabel.text = p.label
        holder.b.txtCarrier.text = "${p.carrierName}  ·  ${p.operatorNumeric}"
        holder.b.pillSub.text = "sub ${p.subscriptionId}"
        holder.b.pillSlot.text = "slot ${p.slotIndex}"
        holder.b.pillEsim.visibility = if (p.isEmbedded) View.VISIBLE else View.GONE

        // Dim everything but the switch when the SIM is off.
        val dim = if (p.active) 1f else 0.4f
        holder.b.iconTile.alpha = dim
        holder.b.txtLabel.alpha = dim
        holder.b.txtCarrier.alpha = dim
        holder.b.pillRow.alpha = dim

        holder.b.swActive.setOnCheckedChangeListener(null)
        holder.b.swActive.isChecked = p.active
        holder.b.swActive.setOnCheckedChangeListener { _, checked ->
            // If rejected (e.g. would exceed 2 active), rebind to snap the switch
            // back to the unchanged model value.
            if (!onToggleActive(p, checked)) notifyItemChanged(holder.bindingAdapterPosition)
        }

        holder.b.root.setOnClickListener { onClick(p) }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<SimProfile>) {
        items = newItems
        notifyDataSetChanged()
    }
}
