package com.github.kr328.clash.design.dialog

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.AllProxies
import com.github.kr328.clash.core.model.ManualProxyGroup
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.adapter.ManualMemberCheckAdapter
import com.github.kr328.clash.design.databinding.DialogManualGroupFormBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Modal form for adding / editing a manually defined proxy group.
 *
 * Supported types: select, url-test, fallback and load-balance. Members can
 * be manual proxies, subscription proxies or other manual groups, referenced
 * by name; the member order is editable and significant for fallback and
 * load-balance sticky sessions.
 */
class ManualGroupFormDialog(
    private val context: Context,
    candidates: List<AllProxies>,
    private val initial: ManualProxyGroup?,
    private val existingNames: Set<String>,
) {
    companion object {
        private val TYPE_SELECT = "select"
        private val TYPE_URL_TEST = "url-test"
        private val TYPE_FALLBACK = "fallback"
        private val TYPE_LOAD_BALANCE = "load-balance"

        private val TYPES = listOf(TYPE_SELECT, TYPE_URL_TEST, TYPE_FALLBACK, TYPE_LOAD_BALANCE)

        private val STRATEGIES = listOf(
            "round-robin",
            "consistent-hashing",
            "sticky-sessions",
        )
    }

    private val initialName = initial?.name.orEmpty()
    private val candidates: List<AllProxies> = candidates
    private val selected = LinkedHashSet<String>()

    private val binding = DialogManualGroupFormBinding
        .inflate(context.layoutInflater, null, false)

    init {
        selected.addAll(initial?.proxies.orEmpty())
    }

    private var name: String = initial?.name.orEmpty()
    private var type: String = initial?.type?.takeIf { it in TYPES } ?: TYPE_SELECT

    private lateinit var adapter: ManualMemberCheckAdapter

    private fun memberItems(): List<Pair<String, String>> {
        return candidates.map { p ->
            p.name to (if (p.isGroup) context.getString(R.string.manual_member_group) else p.type)
        }
    }

    private fun rebuildAdapter() {
        val ordered = selected.firstOrNull()?.let {
            // keep selected members on top, in member order
            val chosen = candidates.filter { p -> selected.contains(p.name) }
            val rest = candidates.filterNot { p -> selected.contains(p.name) }
            chosen + rest
        } ?: candidates

        val moveEnabled = type == TYPE_FALLBACK || type == TYPE_LOAD_BALANCE ||
            type == TYPE_URL_TEST

        adapter = ManualMemberCheckAdapter(
            context,
            memberItemsOf(ordered),
            selected,
            moveUp = if (moveEnabled) ({ position -> move(position, -1) }) else null,
            moveDown = if (moveEnabled) ({ position -> move(position, +1) }) else null,
        )

        binding.membersList.layoutManager = LinearLayoutManager(context)
        binding.membersList.adapter = adapter
    }

    private var displayList: List<AllProxies> = candidates

    private fun memberItemsOf(list: List<AllProxies>): List<Pair<String, String>> {
        displayList = list
        return list.map { p ->
            p.name to (if (p.isGroup) context.getString(R.string.manual_member_group) else p.type)
        }
    }

    private fun move(position: Int, direction: Int) {
        val names = selected.toList()
        val from = names.indexOf(displayList.getOrNull(position)?.name ?: return)

        if (from < 0) return

        val to = from + direction

        if (to < 0 || to >= names.size) return

        selected.clear()
        selected.addAll(names.toMutableList().apply {
            val item = removeAt(from)
            add(to, item)
        })

        rebuildAdapter()
    }

    private fun applyTypeVisibility() {
        binding.urlLayout.isVisible = type == TYPE_URL_TEST || type == TYPE_FALLBACK
        binding.intervalLayout.isVisible = type == TYPE_URL_TEST || type == TYPE_FALLBACK
        binding.strategyLayout.isVisible = type == TYPE_LOAD_BALANCE
        binding.toleranceLayout.isVisible = type == TYPE_URL_TEST
    }

    private fun validate(): Boolean {
        var valid = true

        binding.nameLayout.error = null
        binding.errorView.visibility = View.GONE

        name = binding.nameField.text?.toString()?.trim().orEmpty()

        if (name.isEmpty()) {
            binding.nameLayout.error = context.getString(R.string.manual_error_name_required)
            valid = false
        } else if (existingNames.contains(name.lowercase()) && name != initialName) {
            binding.nameLayout.error = context.getString(R.string.manual_error_duplicate_name)
            valid = false
        }

        if (selected.isEmpty()) {
            binding.errorView.visibility = View.VISIBLE
            valid = false
        }

        return valid
    }

    private fun build(): ManualProxyGroup {
        val options = mutableMapOf<String, String>()

        when (type) {
            TYPE_URL_TEST, TYPE_FALLBACK -> {
                binding.urlField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    options["url"] = it
                }
                binding.intervalField.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    options["interval"] = it.toString()
                }
            }
        }

        when (type) {
            TYPE_LOAD_BALANCE -> {
                binding.strategyField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    // strip the localized decoration, keep raw strategy
                    options["strategy"] = it.substringBefore('（').substringBefore(" (").trim()
                }
            }
            TYPE_URL_TEST -> {
                binding.toleranceField.text?.toString()?.trim()?.toIntOrNull()?.let {
                    options["tolerance"] = it.toString()
                }
            }
        }

        return (initial ?: ManualProxyGroup()).apply {
            this.name = this@ManualGroupFormDialog.name
            this.type = this@ManualGroupFormDialog.type
            this.proxies = selected.toList()
            this.options = options
        }
    }

    fun show(onResult: (ManualProxyGroup?) -> Unit) {
        var result: ManualProxyGroup? = null

        binding.typeField.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                TYPES.map { displayType(it) }
            )
        )
        binding.typeField.setText(displayType(type), false)
        binding.typeField.setOnItemClickListener { _, _, position, _ ->
            type = TYPES[position]
            applyTypeVisibility()
        }

        binding.strategyField.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                STRATEGIES.map { displayStrategy(it) }
            )
        )

        initial?.options?.forEach { (key, value) ->
            when (key) {
                "url" -> binding.urlField.setText(value)
                "interval" -> binding.intervalField.setText(value)
                "tolerance" -> binding.toleranceField.setText(value)
                "strategy" -> binding.strategyField.setText(displayStrategy(value), false)
            }
        }

        applyTypeVisibility()
        rebuildAdapter()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(
                if (initial == null) R.string.manual_new_group else R.string.manual_edit_group
            )
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .create()

        dialog.setOnShowListener {
            initial?.let { group ->
                binding.nameField.setText(group.name)
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (validate()) {
                    result = build()
                    dialog.dismiss()
                }
            }
        }

        dialog.setOnDismissListener {
            onResult(result)
        }

        dialog.show()
    }

    private fun displayType(type: String): String = when (type) {
        TYPE_SELECT -> context.getString(R.string.manual_group_type_select)
        TYPE_URL_TEST -> context.getString(R.string.manual_group_type_url_test)
        TYPE_FALLBACK -> context.getString(R.string.manual_group_type_fallback)
        TYPE_LOAD_BALANCE -> context.getString(R.string.manual_group_type_load_balance)
        else -> type
    }

    private fun displayStrategy(strategy: String): String = when (strategy) {
        "round-robin" -> context.getString(R.string.manual_group_strategy_round_robin)
        "consistent-hashing" -> context.getString(R.string.manual_group_strategy_consistent_hashing)
        "sticky-sessions" -> context.getString(R.string.manual_group_strategy_sticky_sessions)
        else -> strategy
    }
}
