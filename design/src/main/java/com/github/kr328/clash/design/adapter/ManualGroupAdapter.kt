package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.ManualProxyGroup
import com.github.kr328.clash.design.databinding.AdapterManualGroupBinding
import com.github.kr328.clash.design.util.layoutInflater

class ManualGroupAdapter(
    private val context: Context,
    private val onClicked: (ManualProxyGroup) -> Unit,
    private val onMenuClicked: (ManualProxyGroup) -> Unit,
) : RecyclerView.Adapter<ManualGroupAdapter.Holder>() {
    class Holder(val binding: AdapterManualGroupBinding) : RecyclerView.ViewHolder(binding.root)

    var groups: List<ManualProxyGroup> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterManualGroupBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = groups[position]
        val binding = holder.binding

        binding.group = current
        binding.clicked = View.OnClickListener {
            onClicked(current)
        }
        binding.menu = View.OnClickListener {
            onMenuClicked(current)
        }
    }

    override fun getItemCount(): Int {
        return groups.size
    }
}
