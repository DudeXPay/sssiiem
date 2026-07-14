package com.simemu.spoof.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simemu.spoof.core.SimProfile
import com.simemu.spoof.databinding.ItemProfileBinding

class ProfileAdapter(
    private var items: List<SimProfile>,
    private val onClick: (SimProfile) -> Unit
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
        holder.b.root.setOnClickListener { onClick(p) }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<SimProfile>) {
        items = newItems
        notifyDataSetChanged()
    }
}
