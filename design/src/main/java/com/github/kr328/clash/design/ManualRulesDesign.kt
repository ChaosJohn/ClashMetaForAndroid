package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.ManualRule
import com.github.kr328.clash.design.adapter.ManualRuleAdapter
import com.github.kr328.clash.design.databinding.DesignManualRulesBinding
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ManualRulesDesign(context: Context) : Design<ManualRulesDesign.Request>(context) {
    sealed class Request {
        object Create : Request()
        data class Edit(val rule: ManualRule, val index: Int) : Request()
        data class Delete(val index: Int) : Request()
        data class MoveUp(val index: Int) : Request()
        data class MoveDown(val index: Int) : Request()
    }

    private val binding = DesignManualRulesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ManualRuleAdapter(
        context,
        this::requestEdit,
        this::requestMenu,
        this::requestMoveUp,
        this::requestMoveDown,
    )

    var empty: Boolean = true
        private set

    override val root: View
        get() = binding.root

    suspend fun patchRules(rules: List<ManualRule>) {
        adapter.apply {
            swapDataSet(this::rules, rules)
        }

        empty = rules.isEmpty()

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

    private fun requestEdit(rule: ManualRule, index: Int) {
        requests.trySend(Request.Edit(rule, index))
    }

    private fun requestMenu(rule: ManualRule, index: Int) {
        showManualItemMenu(context, rule.rule, R.string.manual_rules,
            onEdit = { requestEdit(rule, index) },
            onDelete = { requests.trySend(Request.Delete(index)) }
        )
    }

    private fun requestMoveUp(index: Int) {
        requests.trySend(Request.MoveUp(index))
    }

    private fun requestMoveDown(index: Int) {
        requests.trySend(Request.MoveDown(index))
    }
}
