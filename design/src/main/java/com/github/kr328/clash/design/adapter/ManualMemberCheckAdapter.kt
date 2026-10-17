package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.ManualProxy
import com.github.kr328.clash.design.databinding.AdapterManualMemberCheckBinding
import com.github.kr328.clash.design.util.layoutInflater

class ManualMemberCheckAdapter(
    private val context: Context,
    private val proxies: List<ManualProxy>,
    private val selected: MutableSet<String>,
) : RecyclerView.Adapter<ManualMemberCheckAdapter.Holder>() {
    class Holder(val binding: AdapterManualMemberCheckBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterManualMemberCheckBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val proxy = proxies[position]
        val binding = holder.binding

        binding.proxy = proxy
        binding.checked = selected.contains(proxy.name)
        binding.clicked = View.OnClickListener {
            if (selected.contains(proxy.name)) {
                selected.remove(proxy.name)
            } else {
                selected.add(proxy.name)
            }

            notifyItemChanged(position)
        }
    }

    override fun getItemCount(): Int {
        return proxies.size
    }
}
