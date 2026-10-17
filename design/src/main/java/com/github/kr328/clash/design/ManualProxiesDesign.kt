package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.ManualConfig
import com.github.kr328.clash.core.model.ManualProxy
import com.github.kr328.clash.design.adapter.ManualProxyAdapter
import com.github.kr328.clash.design.databinding.DesignManualProxiesBinding
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ManualProxiesDesign(context: Context) : Design<ManualProxiesDesign.Request>(context) {
    sealed class Request {
        object Create : Request()
        data class Edit(val proxy: ManualProxy) : Request()
        data class Delete(val proxy: ManualProxy) : Request()
    }

    private val binding = DesignManualProxiesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ManualProxyAdapter(context, this::requestEdit, this::requestMenu)

    var empty: Boolean = true
        private set

    override val root: View
        get() = binding.root

    suspend fun patchConfig(config: ManualConfig) {
        adapter.apply {
            patchDataSet(this::proxies, config.proxies)
        }

        empty = config.proxies.isEmpty()

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

    private fun requestEdit(proxy: ManualProxy) {
        requests.trySend(Request.Edit(proxy))
    }

    private fun requestMenu(proxy: ManualProxy) {
        showManualItemMenu(context, proxy.name, R.string.manual_proxies,
            onEdit = { requestEdit(proxy) },
            onDelete = { requests.trySend(Request.Delete(proxy)) }
        )
    }
}
