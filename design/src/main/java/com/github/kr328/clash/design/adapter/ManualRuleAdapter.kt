package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.ManualRule
import com.github.kr328.clash.design.databinding.AdapterManualRuleBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.swapDataSet

class ManualRuleAdapter(
    private val context: Context,
    private val onClicked: (ManualRule, Int) -> Unit,
    private val onMenuClicked: (ManualRule, Int) -> Unit,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
) : RecyclerView.Adapter<ManualRuleAdapter.Holder>() {
    class Holder(val binding: AdapterManualRuleBinding) : RecyclerView.ViewHolder(binding.root)

    var rules: List<ManualRule> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterManualRuleBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = rules[position]
        val binding = holder.binding

        binding.rule = current
        binding.position = position
        binding.count = rules.size
        binding.clicked = View.OnClickListener {
            onClicked(current, position)
        }
        binding.up = View.OnClickListener {
            onMoveUp(position)
        }
        binding.down = View.OnClickListener {
            onMoveDown(position)
        }
        binding.menu = View.OnClickListener {
            onMenuClicked(current, position)
        }
    }

    override fun getItemCount(): Int {
        return rules.size
    }
}
