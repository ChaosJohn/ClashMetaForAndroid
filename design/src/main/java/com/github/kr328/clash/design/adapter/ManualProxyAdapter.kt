package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.ManualProxy
import com.github.kr328.clash.design.databinding.AdapterManualProxyBinding
import com.github.kr328.clash.design.util.layoutInflater

class ManualProxyAdapter(
    private val context: Context,
    private val onClicked: (ManualProxy) -> Unit,
    private val onMenuClicked: (ManualProxy) -> Unit,
) : RecyclerView.Adapter<ManualProxyAdapter.Holder>() {
    class Holder(val binding: AdapterManualProxyBinding) : RecyclerView.ViewHolder(binding.root)

    var proxies: List<ManualProxy> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterManualProxyBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = proxies[position]
        val binding = holder.binding

        binding.proxy = current
        binding.clicked = View.OnClickListener {
            onClicked(current)
        }
        binding.menu = View.OnClickListener {
            onMenuClicked(current)
        }
    }

    override fun getItemCount(): Int {
        return proxies.size
    }
}
