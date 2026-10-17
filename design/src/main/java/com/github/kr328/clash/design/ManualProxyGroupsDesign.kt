package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.ManualConfig
import com.github.kr328.clash.core.model.ManualProxyGroup
import com.github.kr328.clash.design.adapter.ManualGroupAdapter
import com.github.kr328.clash.design.databinding.DesignManualGroupsBinding
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ManualProxyGroupsDesign(context: Context) : Design<ManualProxyGroupsDesign.Request>(context) {
    sealed class Request {
        object Create : Request()
        data class Edit(val group: ManualProxyGroup) : Request()
        data class Delete(val group: ManualProxyGroup) : Request()
    }

    private val binding = DesignManualGroupsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ManualGroupAdapter(context, this::requestEdit, this::requestMenu)

    var empty: Boolean = true
        private set

    override val root: View
        get() = binding.root

    suspend fun patchConfig(config: ManualConfig) {
        adapter.apply {
            patchDataSet(this::groups, config.proxyGroups, id = ManualProxyGroup::name)
        }

        empty = config.proxyGroups.isEmpty()

        withContext(Dispatchers.Main) {
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    fun requestCreate() {
        requests.trySend(Request.Create)
    }

    private fun requestEdit(group: ManualProxyGroup) {
        requests.trySend(Request.Edit(group))
    }

    private fun requestMenu(group: ManualProxyGroup) {
        showManualItemMenu(context, group.name, R.string.manual_proxy_groups,
            onEdit = { requestEdit(group) },
            onDelete = { requests.trySend(Request.Delete(group)) }
        )
    }
}
