package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterManualMemberCheckBinding
import com.github.kr328.clash.design.util.layoutInflater

/**
 * Member picker for the manual group form. Items are name/subtitle pairs;
 * the currently selected ones are tracked in [selected] (ordered set,
 * iteration order = member order). Non-null [moveUp]/[moveDown] enable the
 * reorder buttons for ordered group types.
 */
class ManualMemberCheckAdapter(
    private val context: Context,
    private val items: List<Pair<String, String>>,
    private val selected: LinkedHashSet<String>,
    private val moveUp: ((position: Int) -> Unit)? = null,
    private val moveDown: ((position: Int) -> Unit)? = null,
) : RecyclerView.Adapter<ManualMemberCheckAdapter.Holder>() {
    class Holder(val binding: AdapterManualMemberCheckBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterManualMemberCheckBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (name, subtitle) = items[position]
        val binding = holder.binding

        binding.name = name
        binding.subtitle = subtitle
        binding.checked = selected.contains(name)
        binding.clicked = View.OnClickListener {
            if (selected.contains(name)) {
                selected.remove(name)
            } else {
                selected.add(name)
            }

            notifyItemChanged(position)
        }
        binding.moveUp = moveUp?.let { callback ->
            View.OnClickListener { callback(position) }
        }
        binding.moveDown = moveDown?.let { callback ->
            View.OnClickListener { callback(position) }
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }
}
